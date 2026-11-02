package app.haanf.heartrate.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import app.haanf.core.Clock
import app.haanf.core.SystemTimeClock
import app.haanf.heartrate.HeartRateDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach

/** One nearby device as Bluetooth reports it. Only the scan and diagnostic screens use this type. */
data class SeenDevice(
    val address: String,
    val name: String?,
    val rssi: Int? = null,
    val connectable: Boolean? = null,
    val services: List<String> = emptyList(),
    val serviceData: List<String> = emptyList(),
    val manufacturers: List<String> = emptyList(),
    val advertisesHeartRate: Boolean = false,
    val paired: Boolean = false,
    val connectedToPhone: Boolean = false,
    val radio: String = "unknown",
    val lastSeenElapsedMs: Long? = null,
) {
    fun toHeartRateDevice() = HeartRateDevice(address, name, BLE_SOURCE_KIND)
}

data class ScanSnapshot(
    val devices: List<SeenDevice> = emptyList(),
    val error: String? = null,
)

/**
 * Lists nearby devices. Devices already paired with the phone are included even when
 * they send no advertisements, because a watch paired to this phone may never appear
 * in a scan and its heart rate service can still answer a direct connection.
 */
@SuppressLint("MissingPermission")
class BleDiscovery(
    context: Context,
    private val clock: Clock = SystemTimeClock,
) {
    private val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)

    fun scan(): Flow<ScanSnapshot> = callbackFlow {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            trySend(ScanSnapshot(error = "Bluetooth is off or unavailable"))
            awaitClose { }
            return@callbackFlow
        }

        val seen = linkedMapOf<String, SeenDevice>()
        seedKnownDevices(adapter).forEach { seen[it.address] = it }
        trySend(ScanSnapshot(ordered(seen)))

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(seen) {
                    val isNew = result.device.address !in seen
                    val merged = merge(seen[result.device.address], result)
                    seen[result.device.address] = merged
                    if (isNew && (merged.advertisesHeartRate || merged.paired)) {
                        Log.i(TAG, "seen ${merged.name} services=${merged.services}")
                    }
                    trySend(ScanSnapshot(ordered(seen)))
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(ScanSnapshot(ordered(seen), error = "Scan failed, code $errorCode"))
            }
        }

        val scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, settings, callback)
        awaitClose { if (adapter.isEnabled) scanner?.stopScan(callback) }
    }.conflate().onEach { delay(REFRESH_MS) }

    private fun seedKnownDevices(adapter: BluetoothAdapter): List<SeenDevice> {
        val connected = manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        val connectedAddresses = connected.map { it.address }.toSet()
        return (adapter.bondedDevices.orEmpty() + connected).distinctBy { it.address }.map {
            SeenDevice(
                address = it.address,
                name = it.name,
                paired = it.bondState == BluetoothDevice.BOND_BONDED,
                connectedToPhone = it.address in connectedAddresses,
                radio = radioName(it.type),
            )
        }
    }

    private fun merge(previous: SeenDevice?, result: ScanResult): SeenDevice {
        val record = result.scanRecord
        val serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid }
        val manufacturers = record?.manufacturerSpecificData
        return SeenDevice(
            address = result.device.address,
            name = record?.deviceName ?: result.device.name ?: previous?.name,
            rssi = result.rssi,
            connectable = result.isConnectable,
            services = serviceUuids.map(GattNames::describe),
            serviceData = record?.serviceData?.keys.orEmpty().map { GattNames.describe(it.uuid) },
            manufacturers = List(manufacturers?.size() ?: 0) {
                GattNames.describeManufacturer(manufacturers!!.keyAt(it))
            },
            advertisesHeartRate = GattNames.HEART_RATE_SERVICE in serviceUuids,
            paired = previous?.paired == true || result.device.bondState == BluetoothDevice.BOND_BONDED,
            connectedToPhone = previous?.connectedToPhone == true,
            radio = radioName(result.device.type),
            lastSeenElapsedMs = clock.elapsedMs(),
        )
    }

    private fun ordered(seen: Map<String, SeenDevice>): List<SeenDevice> =
        seen.values.sortedWith(
            compareByDescending<SeenDevice> { it.advertisesHeartRate }
                .thenByDescending { it.paired }
                .thenBy { it.name == null }
                .thenBy { it.name ?: it.address },
        )

    private fun radioName(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "unknown"
    }

    private companion object {
        const val TAG = "HaanfScan"
        const val REFRESH_MS = 750L
    }
}
