package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkBenchmarkTest {
    private val man40 = BenchmarkInputs(ageYears = 40, sex = Sex.MALE, weightKg = 81.65)
    private val woman40 = man40.copy(sex = Sex.FEMALE)

    private fun steadyWalk(minutes: Int, bpm: Int) =
        (0..minutes * 30).map { TimedBpm(it * 2_000L, bpm) }

    @Test
    fun `matches Kline's equation for a worked example`() {
        // 3.086 litres a minute by hand from the abstract's equation, over 81.65 kg.
        assertEquals(37.8, RockportWalkTest.vo2Max(man40, 15.0, 150.0), 0.1)
    }

    @Test
    fun `agrees with the common relative form of the equation`() {
        // 132.853 - 0.0769 lb - 0.3877 age + 6.315 sex - 3.2649 min - 0.1565 bpm gives 37.4.
        assertEquals(37.4, RockportWalkTest.vo2Max(man40, 15.0, 150.0), 1.0)
    }

    @Test
    fun `scores women lower for the same walk, as the equation does`() {
        val male = RockportWalkTest.vo2Max(man40, 15.0, 150.0)
        val female = RockportWalkTest.vo2Max(woman40, 15.0, 150.0)
        assertEquals(30.5, female, 0.1)
        assertTrue(female < male)
    }

    @Test
    fun `a faster walk at a lower heart rate scores higher`() {
        val slow = RockportWalkTest.vo2Max(man40, 18.0, 160.0)
        val fast = RockportWalkTest.vo2Max(man40, 13.0, 140.0)
        assertTrue(fast > slow)
    }

    @Test
    fun `uses the heart rate from the last quarter of the walk only`() {
        val samples = (0..450).map { TimedBpm(it * 2_000L, if (it < 338) 100 else 150) }
        val outcome = RockportWalkTest.evaluate(man40, samples, 0, 900_000) as WalkOutcome.Scored
        assertEquals(150, outcome.heartRateBpm)
        assertEquals(900, outcome.timeSeconds)
        assertEquals(37.8, outcome.vo2Max, 0.1)
    }

    @Test
    fun `refuses to score a mile faster than anyone can walk it`() {
        val outcome = RockportWalkTest.evaluate(man40, steadyWalk(7, 150), 0, 7 * 60_000L)
        assertTrue(outcome is WalkOutcome.NotScored)
    }

    @Test
    fun `refuses to score a walk beyond the time limit`() {
        val outcome = RockportWalkTest.evaluate(man40, steadyWalk(45, 120), 0, 45 * 60_000L)
        assertTrue(outcome is WalkOutcome.NotScored)
    }

    @Test
    fun `refuses to score without heart rate near the end`() {
        val onlyEarly = steadyWalk(15, 150).filter { it.atMs < 600_000 }
        val outcome = RockportWalkTest.evaluate(man40, onlyEarly, 0, 900_000)
        assertTrue(outcome is WalkOutcome.NotScored)
    }

    @Test
    fun `ignores zero readings when scoring`() {
        val zeros = steadyWalk(15, 0)
        assertTrue(RockportWalkTest.evaluate(man40, zeros, 0, 900_000) is WalkOutcome.NotScored)
    }

    @Test
    fun `places results against the norms at the band edges`() {
        assertEquals(FitnessLevel.WELL_BELOW, FitnessNorms.level(30.1, 35, Sex.MALE))
        assertEquals(FitnessLevel.BELOW, FitnessNorms.level(30.2, 35, Sex.MALE))
        assertEquals(FitnessLevel.AVERAGE, FitnessNorms.level(35.9, 35, Sex.MALE))
        assertEquals(FitnessLevel.ABOVE, FitnessNorms.level(49.2, 35, Sex.MALE))
        assertEquals(FitnessLevel.WELL_ABOVE, FitnessNorms.level(56.5, 35, Sex.MALE))
    }

    @Test
    fun `the medians match the published abstract at both ends of the table`() {
        assertEquals(48.0, FitnessNorms.median(25, Sex.MALE), 0.0)
        assertEquals(37.6, FitnessNorms.median(25, Sex.FEMALE), 0.0)
        assertEquals(24.4, FitnessNorms.median(75, Sex.MALE), 0.0)
        assertEquals(18.3, FitnessNorms.median(75, Sex.FEMALE), 0.0)
    }

    @Test
    fun `ages outside the table use its nearest row`() {
        assertEquals(48.0, FitnessNorms.median(17, Sex.MALE), 0.0)
        assertEquals(24.4, FitnessNorms.median(85, Sex.MALE), 0.0)
    }

    @Test
    fun `each age group changes at the decade`() {
        assertEquals(48.0, FitnessNorms.median(29, Sex.MALE), 0.0)
        assertEquals(42.4, FitnessNorms.median(30, Sex.MALE), 0.0)
    }
}
