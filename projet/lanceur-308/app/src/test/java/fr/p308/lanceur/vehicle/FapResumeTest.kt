package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.49 : une régénération coupée puis reprise au trajet suivant n'est comptée qu'une fois (21-22/11). */
class FapResumeTest {
    private val min = 60_000L
    private val hour = 60 * min

    @Test fun repriseFusionnee() {
        // 21/11 22:28 → coupée 22:53 (25 min), reprise le 22/11 01:30 → finie 02:05 (35 min), 46 km plus loin.
        val cut = RegenRecord(0, 25 * min, 141_960, interrupted = true)
        val end = RegenRecord(3 * hour, 3 * hour + 35 * min, 142_006, interrupted = false)
        assertTrue(FapRegen.isResume(cut, end))
        val merged = FapRegen.mergeRecords(listOf(cut), end)
        assertEquals(1, merged.size)
        assertFalse(merged[0].interrupted)
        assertEquals(60, merged[0].minutes)          // 25 + 35 min de régénération, moteur coupé non compté
        assertEquals(141_960, merged[0].km)          // kilométrage du début
    }

    @Test fun regenerationsDistinctes() {
        val done = RegenRecord(0, 15 * min, 141_000, interrupted = false)
        val next = RegenRecord(2 * hour, 2 * hour + 15 * min, 141_050, interrupted = false)
        assertFalse("la précédente était finie", FapRegen.isResume(done, next))
        val cut = RegenRecord(0, 10 * min, 141_000, interrupted = true)
        assertFalse("trop loin (200 km)", FapRegen.isResume(cut, next.copy(km = 141_200)))
        assertFalse("trop tard (2 jours)", FapRegen.isResume(cut, next.copy(startMs = 48 * hour, endMs = 48 * hour + min)))
        assertEquals(2, FapRegen.mergeRecords(listOf(done), next).size)
    }
}
