package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Détection des régénérations du FAP par le débit d'air, rejouée sans la voiture : apprentissage du débit normal,
 * une vraie régénération, un débit haut trop court (ignoré), une régénération coupée par l'arrêt du moteur.
 * Un seul scénario dans l'ordre : FapRegen garde son état d'un appel à l'autre, comme dans la voiture.
 */
class FapRegenTest {
    private var now = 1_000_000L

    /** Débit d'air « normal » inventé pour le test, différent pour chaque case régime × charge. */
    private fun normalMaf(rpm: Int, load: Int) = 8f + rpm / 250f + load / 10f

    private fun tick(rpm: Int, load: Int, factor: Float = 1f, km: Int? = 140_000) {
        now += 1000
        val e = EngineState(connected = true, rpm = rpm, loadPct = load, coolantC = 90, mafGs = normalMaf(rpm, load) * factor)
        FapRegen.tick(e, 50f, km, now)
    }

    private fun engineOff() {
        now += 1000
        FapRegen.tick(EngineState(connected = true, rpm = 0), 0f, 140_000, now)
    }

    @Test
    fun `apprentissage puis regenerations detectees, ignorees ou interrompues`() {
        // 1. Apprentissage : 12 cases (4 régimes × 3 charges), 45 relevés chacune.
        for (rpm in listOf(1000, 1250, 1500, 1750)) for (load in listOf(20, 30, 40)) repeat(45) { tick(rpm, load) }
        assertEquals(100, FapRegen.state.value.learnPct)
        assertFalse(FapRegen.state.value.active)

        // Moteur froid : même avec un débit élevé, rien n'est compté (EGR fermée de toute façon).
        repeat(200) {
            now += 1000
            FapRegen.tick(EngineState(connected = true, rpm = 1500, loadPct = 30, coolantC = 40, mafGs = 40f), 50f, 140_000, now)
        }
        assertFalse(FapRegen.state.value.active)

        // 2. Vraie régénération : débit 50 % au-dessus de la normale pendant 10 minutes.
        val regenStart = now
        var detectedAfter = -1
        repeat(600) { i ->
            tick(1500, 30, 1.5f)
            if (detectedAfter < 0 && FapRegen.state.value.active) detectedAfter = i + 1
        }
        assertTrue("régénération non détectée", detectedAfter > 0)
        assertTrue("détectée trop tard : $detectedAfter s", detectedAfter <= 150)
        assertEquals(1.5f, FapRegen.state.value.ratio!!, 0.05f) // le débit normal n'a pas été « appris » pendant la régénération

        // Retour à la normale : la régénération se termine et est enregistrée.
        repeat(200) { tick(1500, 30) }
        var s = FapRegen.state.value
        assertFalse(s.active)
        assertEquals(1, s.records.size)
        val r = s.records.first()
        assertFalse(r.interrupted)
        assertEquals(140_000, r.km)
        assertTrue("durée ${r.minutes} min", r.minutes in 9..11)
        assertTrue("début mal daté", kotlin.math.abs(r.startMs - regenStart) < 90_000L)
        assertEquals(1.0f, s.ratio!!, 0.05f)

        // 3. Débit haut pendant 2 min 30 seulement (forte accélération, côte…) : ignoré.
        repeat(150) { tick(1500, 30, 1.5f) }
        repeat(200) { tick(1500, 30) }
        s = FapRegen.state.value
        assertFalse(s.active)
        assertEquals(1, s.records.size)

        // 4. Régénération coupée par l'arrêt du moteur : notée « interrompue ».
        repeat(300) { tick(1250, 30, 1.6f, km = 140_450) }
        assertTrue(FapRegen.state.value.active)
        engineOff()
        s = FapRegen.state.value
        assertFalse(s.active)
        assertEquals(2, s.records.size)
        assertTrue(s.records.first().interrupted)
        assertEquals(140_450, s.records.first().km)
        // La dernière régénération complète reste la première.
        assertEquals(r.endMs, FapRegen.lastCompleteEndMs())
    }
}
