package com.unsilence.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class LineToWaveMathTest {

    private val twoPi = (2 * PI).toFloat()

    @Test
    fun `endpoints stay pinned to the baseline at every phase`() {
        for (step in 0..16) {
            val phase = twoPi * step / 16f
            assertEquals("left endpoint at phase=$phase", 0f, waveDisplacement(0f, phase), 1e-3f)
            assertEquals("right endpoint at phase=$phase", 0f, waveDisplacement(1f, phase), 1e-3f)
        }
    }

    @Test
    fun `displacement never exceeds unit amplitude`() {
        for (i in 0..200) {
            val t = i / 200f
            for (step in 0..16) {
                val d = waveDisplacement(t, twoPi * step / 16f)
                assertTrue("d=$d out of range at t=$t", d in -1f..1f)
            }
        }
    }

    @Test
    fun `phase wraps seamlessly at 2pi`() {
        for (i in 0..100) {
            val t = i / 100f
            assertEquals(waveDisplacement(t, 0f), waveDisplacement(t, twoPi), 1e-3f)
        }
    }

    @Test
    fun `midpoint carries full window amplitude`() {
        assertEquals(-1f, waveDisplacement(0.5f, 0f), 1e-3f)
        assertEquals(1f, waveDisplacement(0.5f, PI.toFloat()), 1e-3f)
    }

    @Test
    fun `endpoints stay pinned for any wavelength count`() {
        for (wavelengths in listOf(1f, 1.5f, 2f, 2.5f)) {
            assertEquals("left at wl=$wavelengths", 0f, waveDisplacement(0f, 1f, wavelengths), 1e-3f)
            assertEquals("right at wl=$wavelengths", 0f, waveDisplacement(1f, 1f, wavelengths), 1e-3f)
        }
    }
}
