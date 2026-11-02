package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateZonesTest {
    private val zones = HeartRateZones(maxBpm = 200)

    @Test
    fun `places heart rates in zones at the band edges`() {
        assertEquals(Zone.RECOVERY, zones.zoneFor(99))
        assertEquals(Zone.EASY, zones.zoneFor(100))
        assertEquals(Zone.EASY, zones.zoneFor(139))
        assertEquals(Zone.HARD, zones.zoneFor(140))
        assertEquals(Zone.HARD, zones.zoneFor(169))
        assertEquals(Zone.VERY_HARD, zones.zoneFor(170))
        assertEquals(Zone.VERY_HARD, zones.zoneFor(210))
    }

    @Test
    fun `gives target ranges that agree with the zones`() {
        assertEquals(100 until 140, zones.rangeFor(Zone.EASY))
        assertEquals(140 until 170, zones.rangeFor(Zone.HARD))
        assertEquals(170..200, zones.rangeFor(Zone.VERY_HARD))
        val odd = HeartRateZones(maxBpm = 187)
        Zone.entries.forEach { zone ->
            val range = odd.rangeFor(zone)
            assertEquals(zone, odd.zoneFor(range.first.coerceAtLeast(1)))
            assertEquals(zone, odd.zoneFor(range.last))
        }
    }

    @Test
    fun `estimates maximum heart rate with the Tanaka formula`() {
        assertEquals(187, MaxHeartRate.estimate(30))
        assertEquals(173, MaxHeartRate.estimate(50))
    }

    @Test
    fun `a measured maximum beats the estimate`() {
        assertEquals(195, MaxHeartRate.resolve(age = 30, measured = 195))
        assertEquals(187, MaxHeartRate.resolve(age = 30, measured = null))
    }
}
