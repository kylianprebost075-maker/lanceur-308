package fr.p308.lanceur.sound

import org.junit.Assert.assertEquals
import org.junit.Test

/** Touches de la balance « Seul / Passagers » dans l'égaliseur FYT (v0.31). */
class ChampTapsTest {
    @Test fun authorSoloSetting() {
        // Réglage de l'auteur relevé le 11/11 : 1 cran à droite, 3 vers l'avant.
        val t = ChampTaps.taps(1, 3)
        assertEquals(listOf(ChampTaps.TAB, ChampTaps.ALL, ChampTaps.RIGHT, ChampTaps.UP, ChampTaps.UP, ChampTaps.UP), t)
    }

    @Test fun passengersIsCentre() {
        assertEquals(0 to 0, ChampTaps.position(SeatMode.PASSENGERS, listOf(109, 128, 75, 115)))
        assertEquals(listOf(ChampTaps.TAB, ChampTaps.ALL), ChampTaps.taps(0, 0))
    }

    @Test fun soloFollowsDistances() {
        // Tweeter gauche plus près (109 contre 128 cm) : le point part vers la droite, 2 crans devant.
        val (r, f) = ChampTaps.position(SeatMode.SOLO, listOf(109, 128, 75, 115))
        assertEquals(1, r)
        assertEquals(FytStage.FRONT_STEPS, f)
        assertEquals(1 to 3, ChampTaps.position(SeatMode.SOLO, listOf(0, 0, 0, 0)))
        // Réglage trouvé au micro du PC : prioritaire.
        assertEquals(1 to 3, ChampTaps.position(SeatMode.SOLO, listOf(109, 128, 75, 115), 1 to 3))
    }

    @Test fun negativeAndScaled() {
        val t = ChampTaps.taps(-2, -1, 1024, 600)
        assertEquals(5, t.size)
        assertEquals(730 * 1024 / 1280 to 356 * 600 / 720, t[2])
        assertEquals(941 * 1024 / 1280 to 544 * 600 / 720, t[4])
        assertEquals(10, ChampTaps.taps(20, 0).size) // limité à 8 crans
    }
}
