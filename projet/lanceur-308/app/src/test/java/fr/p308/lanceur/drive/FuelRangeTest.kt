package fr.p308.lanceur.drive

import fr.p308.lanceur.maintenance.FuelEntry
import fr.p308.lanceur.maintenance.TankLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Autonomie recalculée par le Lanceur : depuis le dernier plein complet ou depuis le niveau saisi (v0.33). */
class FuelRangeTest {
    private val d = LocalDate.of(2026, 11, 1)
    private fun fill(km: Int, liters: Float, full: Boolean = true, day: Long = 0) =
        FuelEntry(date = d.plusDays(day), km = km, liters = liters, price = liters * 1.7f, full = full)

    @Test fun nothingKnownGivesNothing() {
        assertNull(FuelRange.compute(emptyList(), emptyList(), 140_000))
        assertNull(FuelRange.compute(listOf(fill(139_000, 40f)), emptyList(), null)) // pas de kilométrage
    }

    @Test fun fromLastFullFill() {
        // Un seul plein : conso par défaut 6 L/100. 300 km plus loin : 60 − 18 = 42 L, (42 − 6) / 6 × 100 = 600 km.
        val r = FuelRange.compute(listOf(fill(140_000, 45f)), emptyList(), 140_300)!!
        assertEquals(42.0, r.liters, 0.01)
        assertEquals(600, r.km)
        assertEquals(700, r.kmToEmpty)
        assertEquals(300, r.sinceKm)
        assertTrue(r.start.startsWith("plein complet"))
    }

    @Test fun typedLevelWithoutAnyFill() {
        // « Il me reste 25 L » à 140 000 km, 100 km plus loin : 25 − 6 = 19 L, (19 − 6) / 6 × 100 = 216 km.
        val r = FuelRange.compute(emptyList(), emptyList(), 140_100, TankLevel(25f, 140_000, d))!!
        assertEquals(19.0, r.liters, 0.01)
        assertEquals(216, r.km)
        assertTrue(r.start.contains("niveau saisi"))
    }

    @Test fun newestStartingPointWins() {
        val fills = listOf(fill(140_000, 45f))
        // Niveau saisi après le plein : c'est lui qui compte.
        assertEquals(20.0, FuelRange.compute(fills, emptyList(), 140_500, TankLevel(20f, 140_500, d.plusDays(5)))!!.liters, 0.01)
        // Plein complet fait après le niveau saisi : le plein reprend la main.
        val later = fills + fill(140_800, 50f, day = 9)
        assertEquals(60.0, FuelRange.compute(later, emptyList(), 140_800, TankLevel(20f, 140_500, d.plusDays(5)))!!.liters, 0.01)
    }

    @Test fun partialFillAfterTypedLevelIsAdded() {
        val fills = listOf(fill(140_000, 45f), fill(140_600, 15f, full = false, day = 6))
        val r = FuelRange.compute(fills, emptyList(), 140_600, TankLevel(10f, 140_500, d.plusDays(5)))!!
        assertEquals(10.0 - 6.0 + 15.0, r.liters, 0.01) // 100 km à 6 L/100, puis 15 L versés
    }

    @Test fun neverAboveTankNorBelowZero() {
        assertEquals(FuelRange.TANK_L, FuelRange.compute(emptyList(), emptyList(), 140_000, TankLevel(90f, 140_000, d))!!.liters, 0.01)
        val empty = FuelRange.compute(emptyList(), emptyList(), 141_500, TankLevel(10f, 140_000, d))!!
        assertEquals(0.0, empty.liters, 0.01)
        assertEquals(0, empty.km)
        assertNull(FuelRange.compute(emptyList(), emptyList(), 143_000, TankLevel(50f, 140_000, d))) // trop ancien
    }
}
