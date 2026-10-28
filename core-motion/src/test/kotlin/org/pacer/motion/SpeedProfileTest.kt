package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeedProfileTest {

    @Test
    fun `run starts at zero and reaches target distance`() {
        val run = Humanizer.simulate(MotionConfig.run(), distanceMeters = 1000.0, seed = 1L)
        assertEquals(0.0, run.samples.first().speedMps, 1e-9)
        assertEquals(1000.0, run.totalDistanceMeters, 1e-6)
        assertTrue(run.samples.size > 10)
    }

    @Test
    fun `a cap that never lets the subject move terminates instead of spinning`() {
        val config = MotionConfig(
            baseSpeedMps = 3.0,
            acquireDurationSec = 2.0,
            pausesPerMinute = 0.0,
        )
        val samples = SpeedProfile(config, seed = 5L)
            .generate(distanceMeters = 100.0, dtSec = 1.0) { 0.0 }
        // The guard runs longer than the longest legitimate pause, then gives up with
        // a partial run: an early end is recoverable, a hung target process is not.
        assertTrue("too many samples: ${samples.size}", samples.size < 200)
        assertTrue(samples.last().distanceMeters < 100.0)
    }

    @Test
    fun `speeds are never negative`() {
        val run = Humanizer.simulate(MotionConfig.walk(), distanceMeters = 400.0, seed = 2L)
        assertTrue(run.samples.all { it.speedMps >= 0 })
    }

    @Test
    fun `contains acquire ramp and at least cruise`() {
        val run = Humanizer.simulate(MotionConfig.run(), distanceMeters = 2000.0, seed = 3L)
        val phases = run.samples.map { it.phase }.toSet()
        assertTrue(phases.contains(MotionPhase.ACQUIRE))
        assertTrue(phases.contains(MotionPhase.CRUISE))
    }

    @Test
    fun `different seeds diverge`() {
        val a = Humanizer.simulate(MotionConfig.run(), distanceMeters = 1000.0, seed = 11L)
        val b = Humanizer.simulate(MotionConfig.run(), distanceMeters = 1000.0, seed = 22L)
        val diff = a.samples.zip(b.samples).count { (x, y) ->
            kotlin.math.abs(x.speedMps - y.speedMps) > 1e-6
        }
        assertTrue("expected divergent curves, got $diff differing samples", diff > 10)
    }

    @Test
    fun `distance speed steps triangle stays physically plausible`() {
        val config = MotionConfig.run()
        val run = Humanizer.simulate(config, distanceMeters = 3000.0, seed = 7L)
        // Average stride = distance / steps must sit in the human band, and the
        // step series must be monotonic.
        val avgStride = run.totalDistanceMeters / run.totalSteps.coerceAtLeast(1)
        assertTrue("avgStride=$avgStride", avgStride in 0.65..1.35)
        for (i in 1 until run.cumulativeSteps.size) {
            assertTrue(run.cumulativeSteps[i] >= run.cumulativeSteps[i - 1])
        }
    }

    @Test
    fun `stride grows with speed`() {
        assertTrue(StrideLinker.strideForSpeed(1.0) < StrideLinker.strideForSpeed(3.0))
        assertTrue(StrideLinker.strideForSpeed(3.0) < StrideLinker.strideForSpeed(6.0))
        // A 3 m/s run must not clamp at the cadence ceiling anymore.
        val cadence = StrideLinker.cadenceForSpeed(3.0, rng = Random(1L))
        assertTrue("cadence=$cadence", cadence < StrideLinker.MAX_CADENCE_SPM)
    }

    @Test
    fun `cadence stays in human band when moving`() {
        val rng = Random(9L)
        val c = StrideLinker.cadenceForSpeed(3.0, rng = rng)
        assertTrue(c in StrideLinker.MIN_CADENCE_SPM..StrideLinker.MAX_CADENCE_SPM)
        assertEquals(0.0, StrideLinker.cadenceForSpeed(0.0, rng = rng), 0.0)
    }

    @Test
    fun `the stride tapers so moving speeds get a believable cadence`() {
        // A constant 0.70 m stride at 1.0 m/s implies 86 SPM, which the old 140 floor had to
        // override, so the speed/cadence/stride triangle stopped agreeing up to 1.71 m/s -
        // the first step of the human-run preset is 1.6. The stride now shrinks with speed,
        // which lifts the raw cadence above the floor across the moving band.
        assertTrue(
            "stride must grow monotonically through the taper",
            StrideLinker.strideForSpeed(0.5) < StrideLinker.strideForSpeed(1.0),
        )
        assertTrue(
            "a shuffle is shorter than a run stride",
            StrideLinker.strideForSpeed(1.0) < StrideLinker.strideForSpeed(1.5),
        )
        // At and above 1.1 m/s the raw cadence clears the floor on its own, so the floor
        // no longer rewrites it. This is the same threshold ConsistencyChecker uses to
        // decide a subject is "moving"; the two must agree or the checker flags walking.
        for (speed in listOf(1.1, 1.2, 1.6)) {
            val raw = speed / StrideLinker.strideForSpeed(speed) * 60.0
            assertTrue(
                "raw cadence at $speed m/s was $raw, below the floor",
                raw >= StrideLinker.MIN_CADENCE_SPM,
            )
        }
    }

    @Test
    fun `the triangle holds across the running band`() {
        // speed = cadence * stride / 60 is the invariant that makes steps, distance and
        // cadence describe one motion. It used to fail for every speed the human-run preset
        // actually uses at the start of the run.
        for (speed in listOf(1.2, 1.6, 2.0, 2.6, 3.0, 3.4, 4.0)) {
            val stride = StrideLinker.strideForSpeed(speed)
            val cadence = StrideLinker.cadenceForSpeed(speed, stride, rng = Random(3L))
            val implied = cadence * stride / 60.0
            assertEquals("speed $speed", speed, implied, 0.12)
        }
    }

    @Test
    fun `signal realism warms up and holds bearing when still`() {
        val sim = SignalRealism(seed = 5L)
        val cold = sim.next(0.0, 3.0, 1.0, 90f)
        val warm = sim.next(60.0, 3.0, 1.0, 90f)
        assertTrue(warm.accuracyMeters < cold.accuracyMeters)
        val still = sim.next(61.0, 0.0, 1.0, 180f)
        assertEquals(90f, still.bearingDeg, 1e-6f)
        assertTrue(cold.satellites in 3..14)
    }

    @Test
    fun `cadence at running pace is a runner's, not a shuffler's`() {
        // Runners turn over 165-185 SPM from a jog to a fast run and let the stride carry the
        // speed; the old table reached 218 SPM at 4.0 m/s, a pace nobody shuffles at.
        for (speed in listOf(2.8, 3.0, 3.4, 4.0)) {
            val cadence = speed / StrideLinker.strideForSpeed(speed) * 60.0
            assertTrue("$speed m/s -> $cadence spm", cadence in 165.0..190.0)
        }
    }

    @Test
    fun `the preset speed is close to what the run averages`() {
        // One click writes baseSpeedMps and nothing else, so this is the figure the Run page
        // promises. It used to come out a third slower: 6:56/km for the 3.0 m/s preset.
        val config = MotionConfig.run().copy(pausesPerMinute = 0.0)
        val totals = (1L..SEEDS).map { seed ->
            SpeedProfile(config, seed).generate(distanceMeters = 3000.0, dtSec = 1.0).last().tSec
        }
        // 3 km at 3.0 m/s is 1000 s; the fade after the first kilometre is the only drift.
        assertEquals("mean total over $SEEDS seeds", 1000.0, totals.average(), 60.0)
    }

    @Test
    fun `a three kilometre jog is not stopped every two minutes`() {
        // Counts ENTRIES into PAUSE, not samples spent there: at 1 Hz one 8-20 s stop is 8-20
        // samples, so counting samples overstates the stops by an order of magnitude.
        var stops = 0
        for (seed in 1L..SEEDS) {
            val samples = SpeedProfile(MotionConfig.run(), seed)
                .generate(distanceMeters = 3000.0, dtSec = 1.0)
            for (i in 1 until samples.size) {
                if (samples[i].phase == MotionPhase.PAUSE &&
                    samples[i - 1].phase != MotionPhase.PAUSE
                ) {
                    stops++
                }
            }
        }
        assertTrue("mean ${stops.toDouble() / SEEDS} stops per run", stops.toDouble() / SEEDS <= 4.0)
    }

    private companion object {
        /** Seeds per average: one seed is a sample of the wobble, not the delivery. */
        const val SEEDS = 12
    }
}
