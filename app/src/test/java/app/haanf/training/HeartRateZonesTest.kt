package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateZonesTest {
    private val zones = HeartRateZones(maxBpm = 200)

    @Test
    fun `by default zones start at 60, 70, 80 and 90 percent of maximum`() {
        assertEquals(listOf(120, 140, 160, 180), zones.starts)
        assertFalse(zones.isCustom)
    }

    @Test
    fun `places heart rates in zones at the band edges`() {
        assertEquals(Zone.ZONE_1, zones.zoneFor(119))
        assertEquals(Zone.ZONE_2, zones.zoneFor(120))
        assertEquals(Zone.ZONE_2, zones.zoneFor(139))
        assertEquals(Zone.ZONE_3, zones.zoneFor(140))
        assertEquals(Zone.ZONE_4, zones.zoneFor(160))
        assertEquals(Zone.ZONE_4, zones.zoneFor(179))
        assertEquals(Zone.ZONE_5, zones.zoneFor(180))
        assertEquals(Zone.ZONE_5, zones.zoneFor(210))
    }

    @Test
    fun `gives target ranges that agree with the zones`() {
        assertEquals(0 until 120, zones.rangeFor(Zone.ZONE_1))
        assertEquals(120 until 140, zones.rangeFor(Zone.ZONE_2))
        assertEquals(160 until 180, zones.rangeFor(Zone.ZONE_4))
        assertEquals(180..200, zones.rangeFor(Zone.ZONE_5))
        val odd = HeartRateZones(maxBpm = 187)
        Zone.entries.forEach { zone ->
            val range = odd.rangeFor(zone)
            assertEquals(zone, odd.zoneFor(range.first.coerceAtLeast(1)))
            assertEquals(zone, odd.zoneFor(range.last))
        }
    }

    @Test
    fun `custom zones are used as set, whatever the maximum`() {
        val custom = HeartRateZones.of(200, "110,130,150,175")
        assertTrue(custom.isCustom)
        assertEquals(Zone.ZONE_1, custom.zoneFor(109))
        assertEquals(Zone.ZONE_3, custom.zoneFor(130))
        assertEquals(Zone.ZONE_5, custom.zoneFor(175))
        val aboveMax = HeartRateZones(maxBpm = 170, starts = listOf(110, 130, 150, 175))
        assertEquals(175..175, aboveMax.rangeFor(Zone.ZONE_5))
    }

    @Test
    fun `saved zones that are missing or broken fall back to the defaults`() {
        assertEquals(HeartRateZones(200), HeartRateZones.of(200, null))
        assertEquals(HeartRateZones(200), HeartRateZones.of(200, "150,140,160,180"))
        assertEquals(HeartRateZones(200), HeartRateZones.of(200, "120,140,160"))
        assertNull(HeartRateZones.decode("120,x,160,180"))
        assertEquals("120,140,160,180", HeartRateZones.encode(zones.starts))
    }

    @Test
    fun `zone 3 and above count as hard, and edits move one zone at a time`() {
        assertEquals(listOf(false, false, true, true, true), Zone.entries.map { it.isHard })
        assertEquals(Zone.ZONE_1, Zone.ZONE_1.easier())
        assertEquals(Zone.ZONE_3, Zone.ZONE_4.easier())
        assertEquals(Zone.ZONE_5, Zone.ZONE_5.harder())
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
