package org.pacer.motion

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * One sampled point of a speed curve.
 *
 * @param tSec elapsed seconds since run start
 * @param speedMps instantaneous speed, always >= 0
 * @param phase motion phase active at this sample
 * @param distanceMeters cumulative distance up to this sample
 */
data class SpeedSample(
    val tSec: Double,
    val speedMps: Double,
    val phase: MotionPhase,
    val distanceMeters: Double,
)

/**
 * Generates a humanized speed curve for a fixed-distance run.
 *
 * Original Pacer design (not ported from any repo):
 * - ACQUIRE ramp: smooth S-curve 0 -> base over [MotionConfig.acquireDurationSec]
 * - CRUISE: Ornstein-Uhlenbeck noise around a slowly fatiguing base, where the base
 *   comes from [MotionConfig.plan] when an interval session is configured and from
 *   [MotionConfig.baseSpeedMps] otherwise. A prescribed pace curve supplies the base
 *   too, but fatigue and pauses are suspended for it; see [prescribedPace].
 * - PAUSE: exponential inter-arrival, uniform duration, DECEL ramp in/out
 * - distance is integrated with the trapezoidal rule so steps stay consistent
 *
 * Owns its own [Random] instance so two runs with different seeds diverge.
 */
class SpeedProfile(
    private val config: MotionConfig,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)

    /** Current value of the mean-reverting cruise-noise process. */
    private var noise = 0.0

    /**
     * Whether this run is a session the user prescribed per kilometre.
     *
     * Such a curve already states the speed at every metre, fatigue included: the runner typed
     * the slower later kilometres himself, so applying [MotionConfig.fatiguePerKm] again
     * charges the same slowdown twice. Unrequested stops are excluded for the same reason, and
     * no control in the UI can switch them off. The acquire ramp and cruise noise stay: those
     * are facts about a runner, not about the syllabus.
     */
    private val prescribedPace: Boolean = config.pacePlan != null

    /**
     * @param speedCapAtDistance optional multiplier in (0, 1] for the cruise speed at a
     *   given travelled distance, used to brake into a turn. It is applied INSIDE the
     *   integration on purpose: scaling the speed afterwards would report a speed the
     *   track's own displacement does not show, which is the kind of internal
     *   disagreement an audit is supposed to find.
     */
    fun generate(
        distanceMeters: Double,
        dtSec: Double = 1.0,
        speedCapAtDistance: ((Double) -> Double)? = null,
    ): List<SpeedSample> {
        require(distanceMeters > 0) { "distanceMeters must be positive" }
        require(dtSec in 0.2..5.0) { "dtSec out of range" }

        val out = ArrayList<SpeedSample>(1024)
        var t = 0.0
        var dist = 0.0
        var phase = MotionPhase.ACQUIRE
        var phaseT = 0.0
        var pauseLeft = 0.0
        var decelFrom = 0.0
        var cruiseBase = config.baseSpeedMps
        var prevSpeed = 0.0
        // Defence in depth for the integration loop: SpeedPlan now rejects a plan
        // whose pass covers no ground, but a cap callback that holds the subject at
        // 0 m/s would still never advance. Give up after longer than the longest
        // legitimate stop rather than hang a target process.
        val stallLimitSec = config.maxPauseSec + config.acquireDurationSec +
            config.decelDurationSec + 30.0
        var stalledSec = 0.0
        var nextPauseAt = drawNextPauseAt()

        out += SpeedSample(0.0, 0.0, MotionPhase.ACQUIRE, 0.0)

        while (dist < distanceMeters) {
            t += dtSec
            phaseT += dtSec
            val remaining = distanceMeters - dist
            // Leave room to stop: never plan to overshoot the finish.
            val stopSpeed = remaining / dtSec
            // Braking into a corner applies whenever the subject is accelerating, not only
            // while cruising: resuming after a pause into a turn is the same situation as
            // reaching it at speed, and leaving the ramp uncapped put full-speed samples on
            // the approach to every turn that followed a stop.
            val speedCap = speedCapAtDistance?.invoke(dist)?.coerceIn(0.0, 1.0) ?: 1.0

            val speed = when (phase) {
                MotionPhase.ACQUIRE -> {
                    val k = min(1.0, phaseT / config.acquireDurationSec)
                    val ramp = k * k * (3 - 2 * k) // smoothstep
                    val target = (targetSpeed(t, dist) * ramp * speedCap).coerceAtLeast(0.0)
                    if (k >= 1.0) transition(MotionPhase.CRUISE).also { phase = it; phaseT = 0.0 }
                    min(target, stopSpeed)
                }
                MotionPhase.CRUISE -> {
                    cruiseBase = fatigued(targetSpeed(t, dist), dist)
                    val noisy = (cruiseBase * (1 + stepNoise(dtSec)) * speedCap)
                        .coerceAtLeast(0.0)
                    if (t >= nextPauseAt) {
                        pauseLeft = config.minPauseSec +
                            rng.nextDouble() * (config.maxPauseSec - config.minPauseSec)
                        decelFrom = prevSpeed
                        transition(MotionPhase.DECEL).also { phase = it; phaseT = 0.0 }
                        min(prevSpeed, stopSpeed)
                    } else {
                        min(noisy, stopSpeed)
                    }
                }
                MotionPhase.DECEL -> {
                    val k = min(1.0, phaseT / config.decelDurationSec)
                    val target = decelFrom * (1 - k * k * (3 - 2 * k))
                    if (k >= 1.0) transition(MotionPhase.PAUSE).also { phase = it; phaseT = 0.0 }
                    min(max(target, 0.0), stopSpeed)
                }
                MotionPhase.PAUSE -> {
                    pauseLeft -= dtSec
                    if (pauseLeft <= 0) {
                        nextPauseAt = t + drawNextPauseAt()
                        transition(MotionPhase.ACQUIRE).also { phase = it; phaseT = 0.0 }
                        min(cruiseBase * 0.1, stopSpeed)
                    } else {
                        0.0
                    }
                }
                MotionPhase.IDLE -> 0.0
            }

            val moved = (prevSpeed + speed) * 0.5 * dtSec
            dist += moved
            if (dist > distanceMeters) dist = distanceMeters
            if (moved > 1e-9) stalledSec = 0.0 else stalledSec += dtSec
            prevSpeed = speed
            out += SpeedSample(t, speed, phase, dist)
            if (stalledSec > stallLimitSec) break
        }
        return out
    }

    private fun transition(to: MotionPhase): MotionPhase = to

    /**
     * The speed the session is aiming for, from whichever source is configured.
     *
     * A timed plan is read off the clock and a pace curve off the distance travelled, which
     * is why both arguments are here. [MotionConfig] forbids setting both, so this never has
     * to decide between two answers.
     */
    private fun targetSpeed(tSec: Double, distanceMeters: Double): Double = when {
        config.pacePlan != null -> config.pacePlan.speedAtDistance(distanceMeters)
        config.plan != null -> config.plan.speedAt(tSec)
        else -> config.baseSpeedMps
    }

    private fun fatigued(speedMps: Double, distanceMeters: Double): Double {
        if (prescribedPace) return speedMps
        // The onset matters more than the rate. Fading from the first metre makes the speed
        // the user typed an upper bound he never runs at; a runner holds his pace for a while
        // and gives it back later, which is also the shape a coach would expect to see.
        val fadedKm = (distanceMeters / 1000.0 - FATIGUE_ONSET_KM).coerceAtLeast(0.0)
        val factor = 1.0 - config.fatiguePerKm * fadedKm
        return (speedMps * factor).coerceAtLeast(speedMps * 0.5)
    }

    private fun stepNoise(dtSec: Double): Double {
        // OU step: dx = -theta * x * dt + sigma * sqrt(dt) * N(0,1).
        // Sigma scaled so the stationary std ~= cruiseNoiseFraction.
        val sigma = config.cruiseNoiseFraction * kotlin.math.sqrt(2 * config.noiseMeanReversion)
        noise += -config.noiseMeanReversion * noise * dtSec +
            sigma * kotlin.math.sqrt(dtSec) * rng.nextGaussian()
        return noise.coerceIn(-0.5, 0.5)
    }

    private fun drawNextPauseAt(): Double {
        // Checked before the draw, so a prescribed run consumes no random numbers for pauses
        // and its noise sequence is identical to one with pauses switched off.
        if (prescribedPace) return Double.POSITIVE_INFINITY
        if (config.pausesPerMinute <= 0) return Double.POSITIVE_INFINITY
        val ratePerSec = config.pausesPerMinute / 60.0
        return -kotlin.math.ln(1 - rng.nextDouble()) / ratePerSec
    }

    private fun Random.nextGaussian(): Double {
        var u: Double
        var v: Double
        do {
            u = nextDouble()
            v = nextDouble()
        } while (u <= Double.MIN_VALUE)
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) *
            kotlin.math.cos(2.0 * kotlin.math.PI * v)
    }

    companion object {
        /**
         * Distance a run holds its prescribed speed before fatigue is charged.
         *
         * One kilometre because that is the first split a runner judges himself on: the
         * number he typed should be the number he sees there, and the fade should start
         * after it rather than from the starting line.
         */
        const val FATIGUE_ONSET_KM = 1.0
    }
}
