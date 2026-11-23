package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.44 : FAP lu dans le calculateur moteur (21 CA, décodage PSA-RE). */
class FapEcuTest {
    // Réponse multi-trames d'un ELM327 (en-têtes masqués, sans espaces) : 19 octets = 61 CA + 17 octets de données,
    // la dernière trame complétée par du remplissage (00 00).
    private val multi = "013\r0:61CA0102030405\r1:B4401C80000000\r2:00000027C20000"

    @Test fun readsMultiFrameResponse() {
        val d = FapEcu.kwpData(multi, 0xCA)!!
        assertTrue(d.size >= 17)
        val r = FapEcu.decodeCA(d, 0L)!!
        assertEquals(635, r.cataC)          // octet 6 = 0xB4 = 180 × 3,5294
        assertEquals(251, r.pressureMbar)   // octet 7 = 0x40 = 64 × 3,9216
        assertEquals(50, r.loadPct)         // octet 9 = 0x80 = 128 × 0,3922
        assertEquals(1002, r.avgKmBetween)  // octet 16 = 0x27 = 39 × 25,681
        assertEquals(4982, r.kmSinceRegen)  // octet 17 = 0xC2 = 194 × 25,681
    }

    @Test fun refusalAndShortAnswersGiveNothing() {
        assertNull(FapEcu.kwpData("7F2112", 0xCA))
        assertNull(FapEcu.kwpData("NO DATA", 0xCA))
        assertNull(FapEcu.decodeCA(intArrayOf(1, 2, 3), 0L))
        // Réponse avec espaces et en-tête de longueur.
        assertEquals(listOf(0x61, 0xCA, 0x01), FapEcu.elmBytes("61 CA 01"))
    }

    @Test fun givesUpAfterThreeRefusals() {
        val day = 24 * 3_600_000L
        assertEquals(day, FapEcu.retryDelayMs(1))
        assertEquals(day, FapEcu.retryDelayMs(2))
        assertEquals(60 * day, FapEcu.retryDelayMs(3))
        assertEquals(60 * day, FapEcu.retryDelayMs(7))
    }

    @Test fun regenerationSeenWhenKmSinceDropsToZero() {
        fun r(km: Int) = FapEcuReading(0, 40, 50, 300, km, 500)
        assertTrue(FapEcu.regenDone(r(436), r(0)))
        assertTrue(!FapEcu.regenDone(r(436), r(462)))
        assertTrue(!FapEcu.regenDone(null, r(0)))
        assertTrue(!FapEcu.regenDone(r(26), r(0))) // juste après une régénération : pas une nouvelle
    }
}
