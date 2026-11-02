package app.haanf.heartrate.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class GattNamesTest {
    @Test
    fun `names the heart rate service and its UUIDs match the Bluetooth base`() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", GattNames.HEART_RATE_SERVICE.toString())
        assertEquals("00002a37-0000-1000-8000-00805f9b34fb", GattNames.HEART_RATE_MEASUREMENT.toString())
        assertEquals("0x180D Heart Rate", GattNames.describe(GattNames.HEART_RATE_SERVICE))
    }

    @Test
    fun `shows unknown short UUIDs as hex and vendor UUIDs in full`() {
        assertEquals("0xABCD", GattNames.describe(GattNames.shortUuid(0xABCD)))
        val vendor = UUID.fromString("12345678-1234-5678-1234-567812345678")
        assertEquals(vendor.toString(), GattNames.describe(vendor))
    }

    @Test
    fun `labels manufacturer ids`() {
        assertEquals("0x0157 Huami (Amazfit)", GattNames.describeManufacturer(0x0157))
        assertEquals("0xFFFF", GattNames.describeManufacturer(0xFFFF))
    }
}
