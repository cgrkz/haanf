package app.haanf.heartrate.ble

import app.haanf.heartrate.SensorContact

data class HeartRateMeasurement(
    val bpm: Int,
    val sensorContact: SensorContact,
    val energyExpendedKj: Int?,
    val rrIntervalsMs: List<Int>,
)

/** Decodes the Bluetooth Heart Rate Measurement characteristic (0x2A37). */
object HeartRateMeasurementParser {
    private const val FLAG_VALUE_UINT16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY_PRESENT = 0x08
    private const val FLAG_RR_PRESENT = 0x10

    /** Returns null for a malformed payload rather than guessing at a heart rate. */
    fun parse(value: ByteArray): HeartRateMeasurement? {
        if (value.isEmpty()) return null
        val flags = value.u8(0)
        var offset = 1

        val bpm = if (flags and FLAG_VALUE_UINT16 != 0) {
            if (value.size < offset + 2) return null
            value.u16(offset).also { offset += 2 }
        } else {
            if (value.size < offset + 1) return null
            value.u8(offset).also { offset += 1 }
        }

        val contact = when {
            flags and FLAG_CONTACT_SUPPORTED == 0 -> SensorContact.UNSUPPORTED
            flags and FLAG_CONTACT_DETECTED != 0 -> SensorContact.DETECTED
            else -> SensorContact.NOT_DETECTED
        }

        var energy: Int? = null
        if (flags and FLAG_ENERGY_PRESENT != 0) {
            if (value.size < offset + 2) return null
            energy = value.u16(offset)
            offset += 2
        }

        val rr = buildList {
            if (flags and FLAG_RR_PRESENT != 0) {
                while (offset + 1 < value.size) {
                    add(value.u16(offset) * 1000 / 1024)
                    offset += 2
                }
            }
        }

        return HeartRateMeasurement(bpm, contact, energy, rr)
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
}
