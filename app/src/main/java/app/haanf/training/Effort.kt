package app.haanf.training

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Rough figures for each kind of bike. See docs/slice-1-plan.md for their sources. */
enum class BikeType(val label: String, val massKg: Double, val rollingResistance: Double, val dragAreaM2: Double) {
    ROAD("Road bike", 9.0, 0.005, 0.32),
    HYBRID("Hybrid", 13.0, 0.007, 0.45),
    MOUNTAIN("Mountain bike", 14.0, 0.010, 0.50),
}

/**
 * The road cycling power model of Martin and others (1998), without wind or acceleration.
 * Only power that pushes the bike counts, so coasting downhill is zero, never negative.
 */
object CyclingPower {
    private const val GRAVITY = 9.81
    private const val DRIVETRAIN = 0.97
    private const val STANDARD_AIR_DENSITY = 1.225

    fun watts(speedMps: Double, gradeFraction: Double, riderKg: Double, bike: BikeType, airDensity: Double = STANDARD_AIR_DENSITY): Double {
        if (speedMps <= 0) return 0.0
        val mass = riderKg + bike.massKg
        val angle = atan(gradeFraction)
        val rolling = mass * GRAVITY * bike.rollingResistance * cos(angle)
        val climbing = mass * GRAVITY * sin(angle)
        val drag = 0.5 * airDensity * bike.dragAreaM2 * speedMps * speedMps
        return ((rolling + climbing + drag) * speedMps / DRIVETRAIN).coerceAtLeast(0.0)
    }

    /** Air density at [hpa] and 15 °C. Thinner air at altitude makes riding fast cheaper. */
    fun airDensity(hpa: Double?): Double = hpa?.let { it * 100 / (287.05 * 288.15) } ?: STANDARD_AIR_DENSITY
}

/**
 * Minetti and others (2002): the energy cost of running at each gradient. Grade-adjusted
 * speed is the flat-ground speed that would cost the same.
 */
object GradeAdjustedPace {
    private const val STEEPEST = 0.45

    fun costPerMetre(gradeFraction: Double): Double {
        val i = gradeFraction.coerceIn(-STEEPEST, STEEPEST)
        return 155.4 * i.pow(5) - 30.4 * i.pow(4) - 43.3 * i.pow(3) + 46.3 * i.pow(2) + 19.5 * i + 3.6
    }

    fun adjustedSpeed(speedMps: Double, gradeFraction: Double): Double =
        speedMps * costPerMetre(gradeFraction) / costPerMetre(0.0)
}
