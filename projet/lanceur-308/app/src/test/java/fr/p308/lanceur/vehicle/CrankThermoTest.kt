package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.38 : démarrage mesuré par le CAN, verdict corrigé du froid, avis thermostat. */
class CrankThermoTest {
    @Test fun crankLimitFollowsTemperature() {
        assertEquals(9.6f, CrankWatch.crankLimit(null), 0.001f)
        assertEquals(9.6f, CrankWatch.crankLimit(25f), 0.001f)
        assertEquals(9.6f - 0.028f * 21f, CrankWatch.crankLimit(0f), 0.001f)
        assertEquals(8.5f, CrankWatch.crankLimit(-30f), 0.001f)
    }

    @Test fun coldMorningIsJudgedFairly() {
        // 9,2 V au démarreur : faible par 20 °C, seulement « à surveiller » par 0 °C.
        assertEquals(CrankVerdict.WEAK, CrankWatch.judge(12.6f, 9.2f, 20f))
        assertEquals(CrankVerdict.FAIR, CrankWatch.judge(12.6f, 9.2f, 0f))
        assertEquals(CrankVerdict.GOOD, CrankWatch.judge(12.6f, 10.2f, 0f))
        // Au repos sous 12 V : faible quelle que soit la température.
        assertEquals(CrankVerdict.WEAK, CrankWatch.judge(11.9f, null, 0f))
    }

    @Test fun startIsMeasuredFromCanVolts() {
        val t = 10_000_000_000L // loin des autres tests (état partagé)
        CrankWatch.resetSession(t)
        // Contact mis moteur coupé, tension stable (le service FYT ne renvoie que les changements).
        assertNull(CrankWatch.onCanVolts(12.6f, 8, t, outsideC = 5f))
        // 2 min plus tard : chute au démarreur, puis l'alternateur charge.
        assertNull(CrankWatch.onCanVolts(10.1f, 8, t + 120_000, outsideC = 5f))
        val r = CrankWatch.onCanVolts(14.3f, 8, t + 122_000, outsideC = 5f)
        assertNotNull(r)
        assertEquals(12.6f, r!!.restV, 0.001f)
        assertEquals(10.1f, r.minV!!, 0.001f)
        assertEquals(14.3f, r.chargeV!!, 0.001f)
        assertEquals("CAN", r.source)
        // Moteur tournant : plus rien de noté, même si la tension bouge.
        assertNull(CrankWatch.onCanVolts(14.5f, 30, t + 130_000, outsideC = 5f))
        assertNull(CrankWatch.onCanVolts(12.9f, 70, t + 140_000, outsideC = 5f)) // creux bref : pas un arrêt
        assertNull(CrankWatch.onCanVolts(14.4f, 70, t + 150_000, outsideC = 5f))
    }

    @Test fun radioStartedWithEngineRunningGivesNothing() {
        val t = 20_000_000_000L
        CrankWatch.resetSession(t)
        // Moteur arrêté depuis longtemps puis autoradio allumé moteur déjà tourné : pas de repos connu.
        CrankWatch.onCanVolts(12.5f, 20, t, outsideC = 5f)
        CrankWatch.onCanVolts(13.0f, 20, t + 31_000, outsideC = 5f)
        val late = t + 3_600_000L
        CrankWatch.resetSession(late)
        assertNull(CrankWatch.onCanVolts(14.2f, 40, late, outsideC = 5f))
    }

    @Test fun thermostatNeedsThreeStallsInFour() {
        assertNull(Thermostat.advise(listOf(74, null, 73, null)))
        assertNull(Thermostat.advise(listOf(74, 73))) // pas assez de chauffes
        val a = Thermostat.advise(listOf(75, 71, null, 73))
        assertNotNull(a)
        assertEquals(3, a!!.stalls)
        assertEquals(73, a.typicalC)
        assertTrue(a.text.contains("thermostat"))
    }

    @Test fun thermostatHistoryRoundTrips() {
        val list = listOf(74, null, 73)
        assertEquals(list, Thermostat.decode(Thermostat.encode(list)))
    }
}
