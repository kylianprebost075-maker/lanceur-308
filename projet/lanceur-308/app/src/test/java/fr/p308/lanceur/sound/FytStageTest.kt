package fr.p308.lanceur.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Position du point dans la croix « Champ » d'après les distances aux tweeters (v0.29). */
class FytStageTest {
    private fun d(fl: Int, fr: Int, rl: Int = 0, rr: Int = 0) = listOf(fl, fr, rl, rr)

    @Test fun ilFautLesDeuxTweeters() {
        assertNull(FytStage.plan(d(0, 0)))
        assertNull(FytStage.plan(d(80, 0, 120, 130)))
    }

    @Test fun conducteurAGaucheOnDecaleADroite() {
        // Tweeter gauche à 70 cm, droit à 110 cm : le gauche paraît 3,9 dB plus fort → 3 crans à droite.
        val p = FytStage.plan(d(70, 110))!!
        assertTrue(p.nearLeft)
        assertEquals(3.93, p.louderDb, 0.01)
        assertEquals(3, p.rightSteps)
        assertEquals(40, p.gapCm)
        assertEquals(FytStage.FRONT_STEPS, p.frontSteps)
    }

    @Test fun conduiteADroiteOnDecaleAGauche() {
        assertEquals(-3, FytStage.plan(d(110, 70))!!.rightSteps)
    }

    @Test fun egalPasDeDecalage() {
        assertEquals(0, FytStage.plan(d(90, 90))!!.rightSteps)
    }

    @Test fun jamaisPlusDeHuitCrans() {
        assertEquals(FytStage.MAX_STEPS, FytStage.plan(d(20, 300))!!.rightSteps)
    }
}
