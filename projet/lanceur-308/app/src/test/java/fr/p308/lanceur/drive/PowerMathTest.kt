package fr.p308.lanceur.drive

import fr.p308.lanceur.vehicle.CarProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calcul de puissance par accélération (physique pure). */
class PowerMathTest {

    @Test fun densiteDeLAir() {
        assertEquals(1.225, PowerMath.airDensity(15f), 0.001)
        assertTrue("l'air froid est plus dense", PowerMath.airDensity(0f) > PowerMath.airDensity(25f))
    }

    @Test fun forceAuRepos() {
        // À l'arrêt sans accélérer : seule la résistance au roulement = Crr × masse × g.
        val attendu = CarProfile.ROLL_RESIST * CarProfile.MASS_KG * 9.81
        assertEquals(attendu, PowerMath.wheelForceN(0.0, 0.0, 15f), 0.5)
    }

    @Test fun puissanceEtCouple() {
        // 1000 N aux roues à 20 m/s = 1000×20 / rendement / 735,5.
        assertEquals(1000.0 * 20 / CarProfile.DRIVETRAIN_EFF / 735.5, PowerMath.engineCh(1000.0, 20.0), 0.01)
        // 100 ch à 2000 tr/min.
        assertEquals(100.0 * 735.5 / (2000 * 2 * Math.PI / 60.0), PowerMath.torqueNm(100.0, 2000), 0.01)
        assertEquals(0.0, PowerMath.torqueNm(100.0, 0), 0.0)
    }

    @Test fun courbeTropCourteRejetee() {
        assertNull(PowerMath.curve(listOf(Triple(0L, 10f, 1500), Triple(500L, 20f, 2000)), 15f))
    }

    @Test fun accelerationDuModeDemo() {
        // Même profil que le mode démo (Engine.startDemo) : 5 relevés par seconde, de 5 à 43 km/h en 1re « démo ».
        val samples = (1..10).map { k ->
            val x = k * 0.2
            val kmh = 125 * (1 - Math.exp(-x / 5.5)) / (1 - Math.exp(-12 / 5.5))
            Triple((x * 1000).toLong(), kmh.toFloat(), (1300 + (kmh % 45) * 60).toInt())
        }
        assertNotNull(PowerMath.curve(samples, 15f))
    }

    @Test fun courbeDUneAccelerationConstante() {
        // Accélération constante de 2,5 m/s², régime qui monte de 1200 à 4200 : la courbe doit retrouver, au point le
        // plus haut, la puissance calculée directement par la physique (pipeline cohérent malgré lissage et tranches).
        val a = 2.5
        val samples = (0..12).map { k ->
            val t = k * 0.5
            Triple((t * 1000).toLong(), (a * t * 3.6).toFloat(), (1200 + t * 500).toInt())
        }
        val r = PowerMath.curve(samples, 15f)
        assertNotNull("accélération exploitable", r)
        r!!
        assertTrue("courbe non vide", r.points.size >= 3)
        assertTrue("points par régime croissant", r.points.zipWithNext().all { (p, q) -> q.rpm > p.rpm })
        // Point le plus haut en régime : vitesse = a × t, ch attendu par la physique directe.
        val tTop = 11 * 0.5
        val vTop = a * tTop
        val chDirect = PowerMath.engineCh(PowerMath.wheelForceN(a, vTop, 15f), vTop)
        assertEquals(chDirect, r.peakCh.toDouble(), 4.0)
        assertTrue("puissance diesel plausible", r.peakCh in 40..160)
    }
}
