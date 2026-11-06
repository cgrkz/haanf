package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortTest {
    @Test
    fun `25 km per hour on the flat on a road bike is under 100 watts for a 61 kg rider`() {
        val watts = CyclingPower.watts(25 / 3.6, 0.0, 61.0, BikeType.ROAD)
        assertTrue("$watts", watts in 80.0..105.0)
    }

    @Test
    fun `a 7 percent climb at 8 km per hour on a mountain bike is about 145 watts`() {
        val watts = CyclingPower.watts(8.4 / 3.6, 0.07, 61.0, BikeType.MOUNTAIN)
        assertTrue("$watts", watts in 130.0..160.0)
    }

    @Test
    fun `coasting downhill and standing still are zero, never negative`() {
        assertEquals(0.0, CyclingPower.watts(10.0, -0.08, 61.0, BikeType.ROAD), 0.0)
        assertEquals(0.0, CyclingPower.watts(0.0, 0.05, 61.0, BikeType.ROAD), 0.0)
    }

    @Test
    fun `a heavier, more upright bike costs more at the same speed`() {
        val road = CyclingPower.watts(6.0, 0.02, 61.0, BikeType.ROAD)
        val hybrid = CyclingPower.watts(6.0, 0.02, 61.0, BikeType.HYBRID)
        val mountain = CyclingPower.watts(6.0, 0.02, 61.0, BikeType.MOUNTAIN)
        assertTrue(road < hybrid && hybrid < mountain)
    }

    @Test
    fun `thin air at altitude lowers the drag`() {
        assertEquals(1.225, CyclingPower.airDensity(1_013.25), 0.002)
        assertTrue(CyclingPower.airDensity(870.0) < 1.07)
    }

    @Test
    fun `running uphill is worth a faster flat pace, and steep downhill a slower one`() {
        assertEquals(3.6, GradeAdjustedPace.costPerMetre(0.0), 0.0001)
        assertEquals(3.0, GradeAdjustedPace.adjustedSpeed(3.0, 0.0), 0.0001)
        assertTrue(GradeAdjustedPace.adjustedSpeed(3.0, 0.10) > 4.5)
        assertTrue(GradeAdjustedPace.adjustedSpeed(3.0, -0.10) < 2.0)
    }
}
