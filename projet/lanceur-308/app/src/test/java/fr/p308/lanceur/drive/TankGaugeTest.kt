package fr.p308.lanceur.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jauge automatique : litres déduits de l'autonomie de l'ordinateur de bord, plein repéré (v0.33). */
class TankGaugeTest {
    @Test fun litersFollowRange() {
        assertEquals(30.0, TankGauge.litersFromRange(500, 6.0), 0.01) // 500 km à 6 L/100
        assertEquals(0.0, TankGauge.litersFromRange(0, 6.0), 0.01)
        assertEquals(FuelRange.TANK_L, TankGauge.litersFromRange(1900, 6.0), 0.01) // jamais plus que le réservoir
    }

    @Test fun fillIsDetectedWhenRangeJumps() {
        // Autonomie 80 km avant, 880 km après le plein : 800 km × 5,5 L/100 = 44 L ajoutés.
        val f = TankGauge.detectFill(80, 880, 5.5, 1_000L)!!
        assertEquals(44.0, f.liters, 0.01)
        assertEquals(80, f.fromKm)
        assertEquals(880, f.toKm)
    }

    @Test fun smallRiseOrDropIsNotAFill() {
        assertNull(TankGauge.detectFill(500, 530, 6.0, 0)) // conduite plus douce : l'autonomie remonte un peu
        assertNull(TankGauge.detectFill(500, 480, 6.0, 0)) // on roule
        assertNull(TankGauge.detectFill(null, 700, 6.0, 0)) // première valeur reçue : rien à comparer
        assertNull(TankGauge.detectFill(500, 599, 6.0, 0)) // juste sous le seuil
    }

    @Test fun addedLitersNeverExceedTank() {
        assertEquals(FuelRange.TANK_L, TankGauge.detectFill(20, 1500, 9.0, 0)!!.liters, 0.01)
    }

    @Test fun jumpWhileDrivingIsNotAFill() {
        // 13/11 : 920 → 1020 km sur autoroute, l'ordinateur a recalculé avec une conso plus basse.
        assertNull(TankGauge.detectFill(920, 1020, 5.5, 0, moving = true))
        // Le 12/11 à l'arrêt : 90 → 920 km, 830 km × 5,5 L/100 ≈ 45,7 L (et non 60 L à 9,8 L/100).
        assertEquals(45.65, TankGauge.detectFill(90, 920, 5.5, 0, moving = false)!!.liters, 0.01)
    }
}

/** v0.37 : correction de la conso estimée par l'ordinateur de bord. */
class FuelModelBoardTest {
    private fun trip(km: Double, rawL: Double) = TripRecord(0, 0, km, km * 60.0, 0f, 0, 0, 0, (km * 60).toInt(), 0, 0, fuelRawL = rawL)

    @Test fun boardConsumptionCorrectsEstimate() {
        // 100 km estimés à 9,8 L/100, ordinateur de bord à 5,5 : facteur ≈ 0,56.
        val trips = listOf(trip(60.0, 5.88), trip(40.0, 3.92))
        FuelModel.recompute(emptyList(), trips, boardL100 = 5.5)
        assertEquals(5.5 / 9.8, FuelModel.factor, 0.001)
        assertEquals("ordinateur de bord", FuelModel.source)
        assertEquals(5.5, trips[0].l100!!, 0.01)
        FuelModel.recompute(emptyList(), trips, boardL100 = null)
        assertEquals(1.0, FuelModel.factor, 0.0)
    }

    @Test fun notEnoughObdKmKeepsNoCorrection() {
        FuelModel.recompute(emptyList(), listOf(trip(20.0, 2.0)), boardL100 = 5.5)
        assertEquals(1.0, FuelModel.factor, 0.0)
    }

    @Test fun canInstantConsumptionWinsWhenItCoversTheTrip() {
        // 40 km, dont 36 suivis par la conso instantanée du CAN à 5,5 L/100 : 1,98 L → 2,2 L sur 40 km.
        val t = TripRecord(0, 0, 40.0, 2400.0, 0f, 0, 0, 0, 2400, 0, 0, fuelRawL = 3.9, canFuelL = 1.98, canKm = 36.0)
        assertEquals(true, t.fuelFromCan)
        assertEquals(5.5, t.l100!!, 0.01)
        // Couverture trop faible (10 km sur 40) : on revient à l'estimation OBD.
        val u = t.copy(canFuelL = 0.55, canKm = 10.0)
        assertEquals(false, u.fuelFromCan)
    }

    @Test fun boardTripComparison() {
        // Compteur 2 remis à zéro au départ : 0 km, puis 42 km à 5,4 L/100 → 2,268 L.
        val start = mapOf(1 to (685 * 59 / 1000.0 to 685), 2 to (0.0 to 0))
        val end = mapOf(1 to (727 * 59 / 1000.0 to 727), 2 to (42 * 54 / 1000.0 to 42))
        val t = TripLog.boardTripText(start, end)
        assertTrue(t.contains("trajet 1 : compteur à 727 km"))
        assertTrue(t.contains("trajet 2 : 5,4 L/100 sur 42 km"))
        assertEquals("ordinateur de bord : —", TripLog.boardTripText(emptyMap(), end))
    }

    @Test fun canConsumptionDecoding() {
        assertEquals(5.4f, fr.p308.lanceur.vehicle.CanBusSource.decodeConso(54)!!, 0.001f)
        assertNull(fr.p308.lanceur.vehicle.CanBusSource.decodeConso(65535))
        assertNull(fr.p308.lanceur.vehicle.CanBusSource.decodeConso(0))
    }

    @Test fun boardConsumptionIsUsedForRange() {
        val (l100, basis) = FuelRange.consumption(emptyList(), emptyList(), boardL100 = 5.5)
        assertEquals(5.5, l100, 0.0)
        assertEquals("conso de l'ordinateur de bord", basis)
    }
}
