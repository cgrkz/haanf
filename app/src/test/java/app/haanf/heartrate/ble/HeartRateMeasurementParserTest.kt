package app.haanf.heartrate.ble

import app.haanf.heartrate.SensorContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementParserTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun `reads an 8-bit heart rate with no optional fields`() {
        val parsed = HeartRateMeasurementParser.parse(bytes(0x00, 72))!!
        assertEquals(72, parsed.bpm)
        assertEquals(SensorContact.UNSUPPORTED, parsed.sensorContact)
        assertNull(parsed.energyExpendedKj)
        assertEquals(emptyList<Int>(), parsed.rrIntervalsMs)
    }

    @Test
    fun `treats an 8-bit value above 127 as unsigned`() {
        assertEquals(200, HeartRateMeasurementParser.parse(bytes(0x00, 200))!!.bpm)
    }

    @Test
    fun `reads a 16-bit little-endian heart rate`() {
        assertEquals(300, HeartRateMeasurementParser.parse(bytes(0x01, 0x2C, 0x01))!!.bpm)
    }

    @Test
    fun `decodes sensor contact flags`() {
        assertEquals(SensorContact.UNSUPPORTED, HeartRateMeasurementParser.parse(bytes(0x02, 60))!!.sensorContact)
        assertEquals(SensorContact.NOT_DETECTED, HeartRateMeasurementParser.parse(bytes(0x04, 60))!!.sensorContact)
        assertEquals(SensorContact.DETECTED, HeartRateMeasurementParser.parse(bytes(0x06, 60))!!.sensorContact)
    }

    @Test
    fun `converts RR intervals from 1024ths of a second to milliseconds`() {
        val parsed = HeartRateMeasurementParser.parse(bytes(0x10, 60, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(listOf(1000, 500), parsed.rrIntervalsMs)
    }

    @Test
    fun `skips energy before reading RR intervals`() {
        val parsed = HeartRateMeasurementParser.parse(bytes(0x18, 60, 0x10, 0x00, 0x00, 0x04))!!
        assertEquals(16, parsed.energyExpendedKj)
        assertEquals(listOf(1000), parsed.rrIntervalsMs)
    }

    @Test
    fun `ignores a trailing half RR interval`() {
        val parsed = HeartRateMeasurementParser.parse(bytes(0x10, 60, 0x00, 0x04, 0x01))!!
        assertEquals(listOf(1000), parsed.rrIntervalsMs)
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(HeartRateMeasurementParser.parse(bytes()))
        assertNull(HeartRateMeasurementParser.parse(bytes(0x00)))
        assertNull(HeartRateMeasurementParser.parse(bytes(0x01, 60)))
        assertNull(HeartRateMeasurementParser.parse(bytes(0x08, 60, 0x10)))
    }
}
