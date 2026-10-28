package org.pacer.motion

/**
 * Tuning knobs for one simulated run.
 *
 * All defaults are original choices for Pacer; speed presets map to
 * walk / run / ride bands chosen by the caller.
 */
data class MotionConfig(
    /** Target steady speed in m/s. */
    val baseSpeedMps: Double,
    /** Ramp-up time from standstill to cruise, seconds (typical 3..5). */
    val acquireDurationSec: Double = 4.0,
    /** Ramp-down time before a pause, seconds (typical 1..2). */
    val decelDurationSec: Double = 1.5,
    /** Cruise noise amplitude as fraction of base speed (typical 0.10..0.15). */
    val cruiseNoiseFraction: Double = 0.12,
    /**
     * Mean-reversion rate of the cruise noise process (1/s); the correlation
     * time of the wobble is 1/this. Kept at or below 0.1 so the variation lives
     * on a >= 10 s scale: faster wobble is averaged away by the fused provider
     * and by the consumer's own smoothing, which would flatten the speed into
     * something no real runner ever reports. The stationary standard deviation
     * is [cruiseNoiseFraction] regardless of this value, because the noise
     * amplitude is scaled by sqrt(2 * rate) in SpeedProfile.
     */
    val noiseMeanReversion: Double = 0.1,
    /**
     * Fractional speed loss per kilometre, typical 0.02..0.05, charged only after
     * [SpeedProfile.FATIGUE_ONSET_KM].
     *
     * Charging from metre zero made the Run page's speed an upper bound the run never
     * reached: the 3.0 m/s preset averaged 6:56/km.
     */
    val fatiguePerKm: Double = 0.03,

    /**
     * Expected pause events per minute of cruising (simulated traffic/rest).
     *
     * 0.4 interrupted a 3 km jog seven times, and every stop is in the uploaded record.
     */
    val pausesPerMinute: Double = 0.12,
    /** Pause length range in seconds (typical 8..20). */
    val minPauseSec: Double = 8.0,
    val maxPauseSec: Double = 20.0,
    /**
     * Optional interval plan. When set it supplies the speed the run is aiming for at
     * each moment, in place of [baseSpeedMps]; fatigue, noise, pauses and turn braking
     * still apply on top, because those are properties of a person rather than of the
     * session. [baseSpeedMps] remains the reference for anything that needs a single
     * number, including the acquire ramp's fatigue floor.
     */
    val plan: SpeedPlan? = null,
    /**
     * Optional pace curve indexed by distance, for a session prescribed per kilometre.
     *
     * Separate from [plan] because a timed plan says what speed to hold at a moment and a pace
     * curve says what to hold at a place. Only one may be set.
     *
     * [fatiguePerKm] and [pausesPerMinute] do not apply on top of it: the splits already carry
     * the runner's own slowdown, and unrequested stops would break the totals the editor shows.
     * Cruise noise and the acquire ramp still apply.
     */
    val pacePlan: PacePlan? = null,
) {
    init {
        require(baseSpeedMps > 0) { "baseSpeedMps must be positive" }
        require(acquireDurationSec > 0) { "acquireDurationSec must be positive" }
        require(decelDurationSec > 0) { "decelDurationSec must be positive" }
        require(cruiseNoiseFraction in 0.0..0.5) { "cruiseNoiseFraction out of range" }
        require(maxPauseSec >= minPauseSec) { "pause range inverted" }
        require(plan == null || pacePlan == null) {
            "a run has one speed source, not both a timed plan and a pace curve"
        }
    }

    companion object {
        fun walk() = MotionConfig(baseSpeedMps = 1.4)
        fun run() = MotionConfig(baseSpeedMps = 3.0)
        fun ride() = MotionConfig(baseSpeedMps = 6.0)
    }
}
