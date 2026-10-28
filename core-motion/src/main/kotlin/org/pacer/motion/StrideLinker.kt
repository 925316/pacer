package org.pacer.motion

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Links speed to step cadence with a physically consistent triangle:
 * steps = distance / stride, cadence = speed / stride.
 *
 * Original Pacer implementation; numeric bands below are our own choices.
 */
object StrideLinker {
    const val DEFAULT_STRIDE_METERS = 0.7
    const val MIN_CADENCE_SPM = 100.0
    const val MAX_CADENCE_SPM = 220.0
    const val CADENCE_JITTER_SPM = 5.0

    /**
     * Stride length grows with speed (walking shuffles, running strides out).
     * Without this, a 3 m/s run at a fixed 0.7 m stride implies 257 SPM, gets
     * clamped to 220 and steps stop matching distance.
     *
     * Below 1.5 m/s the stride keeps shrinking instead of holding at 0.70. A constant
     * stride there implied absurd cadences: 0.5 m/s at 0.70 m is 43 SPM, which no adult
     * produces, so the counter was floored at 140 and the reported speed/cadence/stride
     * triangle stopped agreeing below about 1.6 m/s - exactly the first step of the
     * human-run preset. Tapering the stride down to 0.42 m at standstill lifts the raw
     * cadence to 58 SPM at 0.5 m/s and 99 at 1.0, so across the running band the floor
     * no longer has to intervene.
     *
     * From 2.8 m/s up the stride grows fast enough to hold cadence near 170-190 instead of
     * running it to the [MAX_CADENCE_SPM] ceiling: the old 1.10 m at 4.0 m/s implied 218 SPM
     * at a 4:10/km pace, and a consumer app prints that number.
     */
    fun strideForSpeed(speedMps: Double): Double = when {
        speedMps <= 1.5 -> 0.42 + (speedMps / 1.5) * 0.28 // 0.42 -> 0.70, 129 SPM
        speedMps <= 2.8 -> 0.70 + (speedMps - 1.5) / 1.3 * 0.28 // 0.70 -> 0.98, 171 SPM
        speedMps <= 4.5 -> 0.98 + (speedMps - 2.8) / 1.7 * 0.44 // 0.98 -> 1.42, 190 SPM
        else -> 1.42 + ((speedMps - 4.5) / 2.0 * 0.25).coerceAtMost(0.25) // up to 1.67
    }

    /**
     * Total steps for a distance, rounded to whole steps.
     */
    fun stepsForDistance(distanceMeters: Double, strideMeters: Double = DEFAULT_STRIDE_METERS): Long {
        require(distanceMeters >= 0) { "distanceMeters must be non-negative" }
        require(strideMeters > 0) { "strideMeters must be positive" }
        return (distanceMeters / strideMeters).roundToLong()
    }

    /**
     * Instantaneous cadence (steps/min) for a speed, with small random jitter,
     * clamped into [MIN_CADENCE_SPM, MAX_CADENCE_SPM]. Zero speed yields zero.
     * When [strideMeters] is omitted the speed-dependent stride is used.
     */
    fun cadenceForSpeed(
        speedMps: Double,
        strideMeters: Double = strideForSpeed(speedMps),
        rng: Random = Random,
    ): Double {
        require(speedMps >= 0) { "speedMps must be non-negative" }
        if (speedMps <= 0.05) return 0.0
        val raw = speedMps / strideMeters * 60.0
        val jittered = raw + (rng.nextDouble() * 2 - 1) * CADENCE_JITTER_SPM
        return jittered.coerceIn(MIN_CADENCE_SPM, MAX_CADENCE_SPM)
    }

    /**
     * Cumulative step counts matching a speed curve sample-by-sample, using the
     * speed-dependent stride so the distance <-> steps <-> cadence triangle
     * stays physically honest. Returns a list parallel to [samples].
     */
    fun cumulativeSteps(
        samples: List<SpeedSample>,
    ): List<Long> {
        var acc = 0.0
        return samples.mapIndexed { idx, s ->
            if (idx == 0) {
                0L
            } else {
                val dt = s.tSec - samples[idx - 1].tSec
                acc += s.speedMps * dt / strideForSpeed(s.speedMps)
                acc.roundToLong()
            }
        }
    }
}
