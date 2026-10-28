package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FrequencyEstimatorTest {

    @Test
    fun `detects 2Hz sine`() {
        val n = 500
        val hz = 2.0
        val vals = DoubleArray(n) { i -> sin(2 * PI * hz * i / 25.0) }
        val spm = FrequencyEstimator.zeroCrossingsPerMinute(25, vals)
        assertTrue("spm=$spm", spm in 110.0..130.0)
    }

    @Test
    fun `correlation peaks near carrier frequency`() {
        val n = 500
        val hz = 2.0
        val vals = DoubleArray(n) { i -> sin(2 * PI * hz * i / 25.0) }
        val stronger = FrequencyEstimator.correlationHz(25, vals, 2.0)
        val weaker = FrequencyEstimator.correlationHz(25, vals, 7.0)
        assertTrue(stronger > weaker)
    }

    @Test
    fun `sensor wave contains low-frequency component`() {
        val curve = Humanizer.simulate(MotionConfig.run(), distanceMeters = 500.0, seed = 9L).samples
        val (acc, _) = SensorWaveGenerator(seed = 9L).generate(curve, sampleHz = 25)
        val vals = acc.map { it.az }.toDoubleArray()
        val spm = FrequencyEstimator.zeroCrossingsPerMinute(25, vals)
        // A 3 m/s run turns over about 175 SPM, roughly 2.9 Hz. Just ensure non-trivial
        // (not dc, not noise).
        assertTrue("spm=$spm", spm in 80.0..260.0)
    }

    @Test
    fun `empty input yields zero`() {
        assertEquals(0.0, FrequencyEstimator.zeroCrossingsPerMinute(25, doubleArrayOf()), 0.0)
    }
}
