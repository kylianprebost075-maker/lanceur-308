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

    @Test fun warmupTargetFollowsThermostat() {
        assertEquals(80, Warmup.effectiveTarget(listOf(null, null, 74, null)))
        // Plafonds à 75, 77, 73 : cible 75 - 3 = 72 °C (médiane des plafonds moins 3).
        assertEquals(72, Warmup.effectiveTarget(listOf(75, 77, null, 73)))
        assertEquals(60, Warmup.effectiveTarget(listOf(55, 52, 54)))
    }

    @Test fun fapWarmThresholdFollowsThermostat() {
        assertEquals(75, FapRegen.warmMinC(80)) // thermostat normal : comme avant
        assertEquals(68, FapRegen.warmMinC(72)) // eau qui plafonne vers 75 °C : mesures gardées dès 68 °C
        assertEquals(60, FapRegen.warmMinC(60))
    }

    @Test fun fapFuelFlowFromInstantConsumption() {
        assertEquals(4.95f, FapRegen.fuelLph(5.5f, 90f)!!, 0.001f) // 5,5 L/100 à 90 km/h = 4,95 L/h
        assertNull(FapRegen.fuelLph(5.5f, 10f)) // trop lent : L/100 × vitesse ne veut plus rien dire
        assertNull(FapRegen.fuelLph(null, 90f))
    }

    @Test fun verdictComesFromLastMeasuredStart() {
        val now = 30_000_000_000L
        val hour = 3_600_000L
        val morning = CrankRecord(now - 9 * hour, 12.5f, 8.6f, 13)                       // mesuré : faible
        val evening = CrankRecord(now, 12.6f, null, 17, 14.3f, 12f, "CAN")               // noté sans la chute
        assertEquals("Repos correct", evening.verdictLabel)
        assertEquals(morning, CrankWatch.forVerdict(listOf(evening, morning), now))
        // Le plus récent est mesuré : c'est lui.
        val measured = CrankRecord(now + hour, 12.6f, 10.4f, 40)
        assertEquals(measured, CrankWatch.forVerdict(listOf(measured, evening, morning), now + hour))
        // Sans la chute mais déjà mauvais au repos : on le montre.
        val flat = CrankRecord(now + 2 * hour, 11.7f, null, 20, source = "CAN")
        assertEquals(flat, CrankWatch.forVerdict(listOf(flat, morning), now + 2 * hour))
        // Mesure trop ancienne (plus de 21 jours) : il ne reste que le démarrage récent.
        val old = morning.copy(atMs = now - 30L * 24 * hour)
        assertEquals(evening, CrankWatch.forVerdict(listOf(evening, old), now))
        assertNull(CrankWatch.forVerdict(emptyList(), now))
    }

    @Test fun idleLoadIsLearnedAndRemovedFromPower() {
        IdleLoad.init(null)
        assertEquals(0f, IdleLoad.pct, 0f)
        // En roulant, moteur froid ou hors ralenti : rien n'est appris.
        assertEquals(false, IdleLoad.feed(820, 36, 30, 80))
        assertEquals(false, IdleLoad.feed(820, 36, 0, 40))
        assertEquals(false, IdleLoad.feed(1500, 36, 0, 80))
        repeat(IdleLoad.MIN_SAMPLES - 1) { assertEquals(false, IdleLoad.feed(820, 35, 0, 75)) }
        assertEquals(true, IdleLoad.feed(820, 35, 0, 75))
        assertEquals(35f, IdleLoad.pct, 0.01f)
        // Au ralenti : plus de couple ni de puissance affichés. À pleine charge : rien ne change.
        assertEquals(0 to 0, estimate(820, 35, IdleLoad.pct))
        assertEquals(estimate(4000, 100), estimate(4000, 100, IdleLoad.pct))
        assertEquals(50f, netLoadPct(68, 36f), 0.01f)
        assertEquals(60f, netLoadPct(60, 0f), 0f) // pas encore apprise : pas de correction
        IdleLoad.init(null)
        assertEquals(0f, IdleLoad.pct, 0f)
    }

    @Test fun thermostatHistoryRoundTrips() {
        val list = listOf(74, null, 73)
        assertEquals(list, Thermostat.decode(Thermostat.encode(list)))
    }
}
