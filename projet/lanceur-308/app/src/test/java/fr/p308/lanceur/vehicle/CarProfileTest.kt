package fr.p308.lanceur.vehicle

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde-fou du profil de voiture (v0.24) : une IA qui adapte CarProfile.kt à une autre voiture lance ces tests ;
 * ils refusent les valeurs impossibles avant même de construire l'appli.
 */
class CarProfileTest {
    @Test fun courbeDeCoupleDansLOrdreEtPlausible() {
        val c = CarProfile.TORQUE_CURVE
        assertTrue("au moins 3 points", c.size >= 3)
        assertTrue("régimes croissants", c.zipWithNext().all { (a, b) -> b.first > a.first })
        assertTrue("régimes entre 500 et 9 000 tr/min", c.all { it.first in 500..9_000 })
        assertTrue("couple entre 20 et 1 200 N·m", c.all { it.second in 20..1_200 })
    }

    @Test fun rapportsDeBoiteCroissants() {
        val g = CarProfile.KMH_PER_1000
        assertTrue("entre 4 et 10 rapports", g.size in 4..10)
        assertTrue("chaque rapport plus long que le précédent", g.toList().zipWithNext().all { (a, b) -> b > a })
        assertTrue("entre 3 et 80 km/h à 1 000 tr/min", g.all { it in 3f..80f })
    }

    @Test fun reservoirCoherent() {
        assertTrue("réservoir entre 20 et 150 L", CarProfile.TANK_L in 20.0..150.0)
        assertTrue("réserve plus petite que le réservoir", CarProfile.RESERVE_L > 0 && CarProfile.RESERVE_L < CarProfile.TANK_L / 2)
    }

    @Test fun nomsRemplis() {
        assertTrue(CarProfile.NAME.isNotBlank() && CarProfile.NAME.length <= 24)
        assertTrue(CarProfile.ENGINE_LABEL.isNotBlank() && CarProfile.ENGINE_LABEL.length <= 24)
    }
}
