package fr.p308.lanceur.drive

import fr.p308.lanceur.maintenance.Due
import fr.p308.lanceur.maintenance.DueLevel
import fr.p308.lanceur.vehicle.BatteryLevel
import fr.p308.lanceur.vehicle.BatteryState
import fr.p308.lanceur.vehicle.CrankRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bilan « Prêt à partir » (v0.26) : chaque règle, sans la voiture. */
class ReadyCheckTest {
    private val now = 1_800_000_000_000L
    private fun crank(restV: Float, minV: Float?, daysAgo: Int = 0) = CrankRecord(now - daysAgo * 86_400_000L, restV, minV, 20)
    private fun lines(
        outside: Float? = null, estimated: Boolean = false, battery: BatteryState = BatteryState(),
        crank: CrankRecord? = null, rangeKm: Int? = null, dues: List<Pair<String, Due>> = emptyList(),
    ) = ReadyCheck.lines(outside, estimated, battery, crank, rangeKm?.let { RangeInfo(it, 30.0, 5.5, "essai") }, dues, now)

    @Test fun sansDonneeAucuneLigne() {
        assertTrue(lines().isEmpty())
        assertEquals("Prêt à partir", ReadyCheck.title(emptyList()))
    }

    @Test fun verglasSeulementAvecUneVraieTemperature() {
        assertEquals(ReadyLevel.ALERT, lines(outside = 2f).single().level)
        assertEquals(ReadyLevel.ALERT, lines(outside = -4f).single().level)
        assertEquals(ReadyLevel.OK, lines(outside = 11f).single().level)
        // Estimée par l'air d'admission : trop chaude pour juger du verglas, donc pas de ligne.
        assertTrue(lines(outside = 2f, estimated = true).isEmpty())
    }

    @Test fun batterieSelonLeDernierDemarrage() {
        assertEquals(ReadyLevel.OK, lines(crank = crank(12.5f, 10.2f)).single().level)
        assertEquals(ReadyLevel.ALERT, lines(crank = crank(11.9f, 7.5f)).single().level)
        assertTrue("démarrage trop ancien ignoré", lines(crank = crank(11.9f, 7.5f, daysAgo = 40)).isEmpty())
        // Une alerte en cours passe avant le dernier démarrage, même bon.
        val alarm = lines(battery = BatteryState(11.4f, BatteryLevel.CRITICAL), crank = crank(12.5f, 10.2f)).single()
        assertEquals(ReadyLevel.ALERT, alarm.level)
    }

    @Test fun carburant() {
        assertEquals(ReadyLevel.ALERT, lines(rangeKm = 30).single().level)
        assertEquals(ReadyLevel.WARN, lines(rangeKm = 80).single().level)
        assertEquals(ReadyLevel.OK, lines(rangeKm = 540).single().level)
    }

    @Test fun entretienLePlusGraveDAbord() {
        val ok = "Pneus" to Due(20_000, 300, 0.2f, DueLevel.OK)
        val soon = "Vidange" to Due(600, 90, 0.9f, DueLevel.SOON)
        val late = "Contrôle technique" to Due(null, -12, 1f, DueLevel.LATE)
        val unset = "Courroie" to Due(null, null, 0f, DueLevel.NOT_SET)
        assertEquals("À jour", lines(dues = listOf(ok, unset)).single().text)
        assertTrue(lines(dues = listOf(unset)).isEmpty())
        val l = lines(dues = listOf(ok, soon, late)).single()
        assertEquals(ReadyLevel.ALERT, l.level)
        assertTrue(l.text.startsWith("Contrôle technique"))
        assertEquals(ReadyLevel.WARN, lines(dues = listOf(ok, soon)).single().level)
    }

    @Test fun titreSelonLeNiveauLePlusGrave() {
        val all = lines(outside = 12f, crank = crank(12.5f, 10.2f), rangeKm = 80)
        assertEquals(3, all.size)
        assertEquals("Prêt à partir, avec un point à surveiller", ReadyCheck.title(all))
        assertEquals("À vérifier avant de partir", ReadyCheck.title(lines(outside = 1f)))
    }
}
