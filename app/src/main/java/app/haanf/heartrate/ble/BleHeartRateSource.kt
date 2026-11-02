package app.haanf.heartrate.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.haanf.core.Clock
import app.haanf.core.SystemTimeClock
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.HeartRateSource
import app.haanf.heartrate.HeartRateSourceFactory
import app.haanf.heartrate.ReconnectPolicy
import app.haanf.heartrate.SourceState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

const val BLE_SOURCE_KIND = "ble-heart-rate-profile"

class BleHeartRateSourceFactory(private val context: Context) : HeartRateSourceFactory {
    override fun create(device: HeartRateDevice): HeartRateSource {
        require(device.sourceKind == BLE_SOURCE_KIND) { "Unknown source ${device.sourceKind}" }
        return BleHeartRateSource(context.applicationContext, device)
    }
}

/**
 * Standard Bluetooth Heart Rate Profile client. All state is touched only on
 * [handler]'s thread, including every GATT callback, so nothing here needs a lock.
 * Callers guarantee the Bluetooth permissions before starting a session.
 */
@SuppressLint("MissingPermission")
class BleHeartRateSource(
    private val context: Context,
    override val device: HeartRateDevice,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val clock: Clock = SystemTimeClock,
) : HeartRateSource {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val thread = HandlerThread("haanf-ble").apply { start() }
    private val handler = Handler(thread.looper)

    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private val _readings = MutableSharedFlow<HeartRateReading>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val readings: Flow<HeartRateReading> = _readings.asSharedFlow()

    private val _notes = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val notes: Flow<String> = _notes.asSharedFlow()

    private var address = device.id
    private var gatt: BluetoothGatt? = null
    private var attempts = 0
    private var stopped = false
    private var finished = false
    private var receiverRegistered = false
    private var scanCallback: ScanCallback? = null

    private val retry = Runnable {
        if (policy.shouldRescan(attempts)) rescanThenConnect() else connect()
    }
    private val setupTimeout = Runnable {
        tearDown()
        scheduleRetry("setup timed out")
    }
    private val scanTimeout = Runnable { finishRescan(found = null) }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bonded = IntentCompat.getParcelableExtra(
                intent,
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            ) ?: return
            if (bonded.address != address) return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            note("bond state $bondState")
            if (_state.value !is SourceState.Pairing) return
            when (bondState) {
                BluetoothDevice.BOND_BONDED -> gatt?.let { resubscribe(it) }
                BluetoothDevice.BOND_NONE -> {
                    tearDown()
                    scheduleRetry("pairing did not complete")
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            note("link state $newState, status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // A bonded peer may still be encrypting; discovering at once can stall.
                handler.postDelayed({ if (g === gatt) g.discoverServices() }, DISCOVERY_DELAY_MS)
            } else {
                linkLost(g, "link lost, status $status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                tearDown()
                scheduleRetry("service discovery failed, status $status")
                return
            }
            val offered = g.services.map { GattNames.describe(it.uuid) }
            note("GATT services: ${offered.joinToString()}")
            val measurement = g.getService(GattNames.HEART_RATE_SERVICE)
                ?.getCharacteristic(GattNames.HEART_RATE_MEASUREMENT)
            if (measurement == null) {
                finishUnsupported("no standard Heart Rate service (0x180D)", offered)
            } else {
                subscribe(g, measurement, offered)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt || descriptor.uuid != GattNames.CLIENT_CONFIG_DESCRIPTOR) return
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> onSubscribed()
                in AUTH_FAILURE_STATUSES -> beginPairing(g, status)
                else -> {
                    tearDown()
                    scheduleRetry("notification setup failed, status $status")
                }
            }
        }

        // Android 13 and newer calls the three-argument version instead of this one.
        @Deprecated("Needed below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            deliver(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(value)
        }
    }

    override fun start() {
        handler.post {
            ContextCompat.registerReceiver(
                context,
                bondReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                null,
                handler,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            connect()
        }
    }

    override fun stop() {
        handler.post {
            stopped = true
            handler.removeCallbacksAndMessages(null)
            stopRescan()
            tearDown()
            if (receiverRegistered) context.unregisterReceiver(bondReceiver)
            receiverRegistered = false
            _state.value = SourceState.Idle
            thread.quitSafely()
        }
    }

    override fun reconnect(reason: String) {
        handler.post {
            if (stopped || finished) return@post
            note("reconnect requested: $reason")
            handler.removeCallbacks(retry)
            stopRescan()
            tearDown()
            scheduleRetry(reason)
        }
    }

    private fun connect() {
        if (stopped || finished) return
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            scheduleRetry("Bluetooth is off")
            return
        }
        if (attempts == 0) _state.value = SourceState.Connecting(attempt = 1)
        handler.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
        try {
            gatt = adapter.getRemoteDevice(address).connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
        } catch (e: SecurityException) {
            handler.removeCallbacks(setupTimeout)
            finished = true
            _state.value = SourceState.Failed("Bluetooth permission is missing")
        }
    }

    private fun subscribe(g: BluetoothGatt, measurement: BluetoothGattCharacteristic, offered: List<String>) {
        val config = measurement.getDescriptor(GattNames.CLIENT_CONFIG_DESCRIPTOR)
        if (config == null) {
            finishUnsupported("Heart Rate Measurement offers no notifications", offered)
            return
        }
        g.setCharacteristicNotification(measurement, true)
        if (!writeEnableNotifications(g, config)) {
            tearDown()
            scheduleRetry("could not request notifications")
        }
    }

    private fun resubscribe(g: BluetoothGatt) {
        val measurement = g.getService(GattNames.HEART_RATE_SERVICE)
            ?.getCharacteristic(GattNames.HEART_RATE_MEASUREMENT) ?: return
        subscribe(g, measurement, g.services.map { GattNames.describe(it.uuid) })
    }

    @Suppress("DEPRECATION")
    private fun writeEnableNotifications(g: BluetoothGatt, config: BluetoothGattDescriptor): Boolean {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(config, enable) == BluetoothStatusCodes.SUCCESS
        } else {
            config.value = enable
            g.writeDescriptor(config)
        }
    }

    private fun onSubscribed() {
        handler.removeCallbacks(setupTimeout)
        attempts = 0
        _state.value = SourceState.Streaming
    }

    private fun beginPairing(g: BluetoothGatt, status: Int) {
        note("pairing needed, status $status")
        _state.value = SourceState.Pairing
        handler.removeCallbacks(setupTimeout)
        handler.postDelayed(setupTimeout, PAIRING_TIMEOUT_MS)
        if (g.device.bondState == BluetoothDevice.BOND_NONE) g.device.createBond()
    }

    private fun deliver(value: ByteArray) {
        val parsed = HeartRateMeasurementParser.parse(value)
        if (parsed == null) {
            note("malformed measurement: ${value.joinToString("") { "%02x".format(it) }}")
            return
        }
        Log.d(TAG, "reading bpm=${parsed.bpm} contact=${parsed.sensorContact} rr=${parsed.rrIntervalsMs}")
        _readings.tryEmit(
            HeartRateReading(
                bpm = parsed.bpm,
                receivedAtEpochMs = clock.nowEpochMs(),
                receivedAtElapsedMs = clock.elapsedMs(),
                sensorContact = parsed.sensorContact,
                rrIntervalsMs = parsed.rrIntervalsMs,
            ),
        )
    }

    private fun linkLost(g: BluetoothGatt, reason: String) {
        if (g !== gatt) {
            g.close()
            return
        }
        tearDown()
        scheduleRetry(reason)
    }

    private fun finishUnsupported(reason: String, offered: List<String>) {
        note("unsupported: $reason")
        finished = true
        tearDown()
        _state.value = SourceState.Unsupported(reason, offered)
    }

    private fun tearDown() {
        handler.removeCallbacks(setupTimeout)
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    private fun scheduleRetry(reason: String) {
        if (stopped || finished) return
        attempts++
        _state.value = SourceState.Reconnecting(attempts, reason)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, policy.delayMs(attempts))
    }

    private fun rescanThenConnect() {
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            connect()
            return
        }
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (result.device.address == address || (name != null && name == device.name)) {
                    handler.post { finishRescan(found = result.device.address) }
                }
            }
        }
        scanCallback = scan
        // A filter is required: Android withholds unfiltered results while the screen is off.
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GattNames.HEART_RATE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scan)
        handler.postDelayed(scanTimeout, RESCAN_MS)
    }

    private fun finishRescan(found: String?) {
        if (scanCallback == null) return
        stopRescan()
        if (found != null && found != address) {
            note("device address changed to $found")
            address = found
        }
        connect()
    }

    private fun stopRescan() {
        handler.removeCallbacks(scanTimeout)
        scanCallback?.let { manager?.adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    private fun note(message: String) {
        Log.i(TAG, message)
        _notes.tryEmit(message)
    }

    private companion object {
        const val TAG = "HaanfBle"
        const val DISCOVERY_DELAY_MS = 600L
        const val SETUP_TIMEOUT_MS = 35_000L
        const val PAIRING_TIMEOUT_MS = 60_000L
        const val RESCAN_MS = 8_000L

        // Insufficient authentication, authorization and encryption, then the stack's auth failure.
        val AUTH_FAILURE_STATUSES = setOf(5, 8, 15, 137)
    }
}
