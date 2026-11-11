package fr.p308.lanceur.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
