package app.haanf.ui

import app.haanf.data.BenchmarkEntity
import app.haanf.data.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class OnboardingStepTest {
    private val today = LocalDate.of(2026, 11, 5)
    private val complete = ProfileEntity(birthYear = 1996, sex = "MALE", weightKg = 75.0, healthCheckClearedAtMs = 1)
    private val benchmark = BenchmarkEntity(
        workoutId = 1, protocol = "rockport-1-mile", takenAtMs = 0, timeSeconds = 900,
        heartRateBpm = 140, ageYears = 30, sex = "MALE", weightKg = 75.0, vo2Max = 41.2,
    )

    private fun step(
        profile: ProfileEntity?,
        benchmarks: List<BenchmarkEntity> = emptyList(),
        watch: Boolean = false,
        finished: Boolean = false,
        welcomed: Boolean = false,
        benchmarkOn: Boolean = true,
    ) = onboardingStep(profile, benchmarks, watch, finished, welcomed, today, benchmarkOn)

    @Test
    fun `starts with the welcome`() {
        assertEquals(OnboardingStep.WELCOME, step(null))
        assertEquals(OnboardingStep.ABOUT_YOU, step(null, welcomed = true))
    }

    @Test
    fun `asks about you until the health check is done`() {
        assertEquals(OnboardingStep.ABOUT_YOU, step(complete.copy(weightKg = null)))
        assertEquals(OnboardingStep.ABOUT_YOU, step(complete.copy(healthCheckClearedAtMs = null)))
    }

    @Test
    fun `then the watch, which can be skipped`() {
        assertEquals(OnboardingStep.CONNECT_WATCH, step(complete))
        assertEquals(OnboardingStep.FIRST_BENCHMARK, step(complete, watch = true))
        assertEquals(OnboardingStep.FIRST_BENCHMARK, step(complete.copy(deviceId = "AA")))
    }

    @Test
    fun `then the first benchmark, unless put off or done`() {
        assertNull(step(complete, watch = true, finished = true))
        assertNull(step(complete, listOf(benchmark), watch = true))
    }

    @Test
    fun `with benchmarks switched off, first run ends after the watch`() {
        assertEquals(OnboardingStep.CONNECT_WATCH, step(complete, benchmarkOn = false))
        assertNull(step(complete, watch = true, benchmarkOn = false))
        assertNull(step(complete.copy(deviceId = "AA"), benchmarkOn = false))
    }

    @Test
    fun `a phone upgraded from an earlier version skips straight in`() {
        // An existing user has a profile and a benchmark, but no onboarding flags yet.
        assertNull(step(complete.copy(deviceId = "AA"), listOf(benchmark)))
    }
}
