package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Canal standard FYT de la température extérieure (v0.28) : dixièmes de degré + 1000. */
class OutsideTempTest {
    @Test fun decodeLesValeursCourantes() {
        assertEquals(16f, OutsideTemp.decodeFyt(1160)!!, 0.01f)
        assertEquals(0f, OutsideTemp.decodeFyt(1000)!!, 0.01f)
        assertEquals(-5.5f, OutsideTemp.decodeFyt(945)!!, 0.01f)
    }

    @Test fun refuseLesValeursSansCapteur() {
        assertNull(OutsideTemp.decodeFyt(0))
        assertNull("bit 30 : pas de donnée", OutsideTemp.decodeFyt((1 shl 30) or 1160))
        assertNull("bit 31 : hors limites", OutsideTemp.decodeFyt((1 shl 31) or 1160))
        assertNull("trop chaud pour être vrai", OutsideTemp.decodeFyt(1000 + 900))
    }

    @Test fun fahrenheitConverti() {
        assertEquals(15.6f, OutsideTemp.decodeFyt((1 shl 28) or (1000 + 601))!!, 0.1f)
    }

    @Test fun lectureDirecteSansApprentissage() {
        assertEquals(16f, OutsideTemp.onValue(0, 40, intArrayOf(1160))!!, 0.01f)
        assertNull(OutsideTemp.onValue(7, 40, intArrayOf(1160)))
    }
}