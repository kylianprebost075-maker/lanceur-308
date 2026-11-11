package fr.p308.lanceur.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Basses selon le volume (v0.30) : plus à volume bas, fréquence qui fait vibrer les portes baissée à volume fort. */
class BassCurveTest {
    /** Bandes habituelles de l'égaliseur d'Android. */
    private val bands = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)

    @Test fun volumeBasPlusDeBasses() {
        val g = BassCurve.gains(bands, volume = 0.0, resonanceHz = 0)
        assertEquals(BassCurve.BOOST_DB, g[0], 0.01)
        assertEquals(BassCurve.BOOST_DB / 2, g[1], 0.01)
        assertEquals("médiums et aigus intacts", 0.0, g.drop(2).sumOf { kotlin.math.abs(it) }, 0.001)
    }

    @Test fun volumeMoyenRienNeChange() {
        assertTrue(BassCurve.gains(bands, volume = 0.5, resonanceHz = 80).all { kotlin.math.abs(it) < 1.1 })
    }

    @Test fun volumeFortLaVibrationEstBaissee() {
        val g = BassCurve.gains(bands, volume = 1.0, resonanceHz = 80)
        assertEquals("bande de 60 Hz, la plus proche de 80 Hz", -BassCurve.CUT_DB, g[0], 0.01)
        assertEquals(0.0, g[1], 0.001)
    }

    @Test fun sansVibrationConnueRienABaisser() {
        assertTrue(BassCurve.gains(bands, volume = 1.0, resonanceHz = 0).all { it == 0.0 })
    }

    @Test fun intensite() {
        assertEquals(BassCurve.BOOST_DB * 1.5, BassCurve.gains(bands, 0.0, 0, strength = 3)[0], 0.01)
        assertEquals(BassCurve.BOOST_DB * 0.5, BassCurve.gains(bands, 0.0, 0, strength = 1)[0], 0.01)
    }

    @Test fun pasDeGraveSous40Hz() {
        val g = BassCurve.gains(listOf(31.0, 62.0, 125.0), volume = 0.0, resonanceHz = 0)
        assertEquals(0.0, g[0], 0.001)
        assertTrue(g[1] > 0 && g[2] > 0)
    }
}
