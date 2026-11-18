package fr.p308.lanceur.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.roundToInt

/** Détection d'une accélération à fond par le banc de puissance (relevés comme ceux de la boucle OBD). */
class PowerRunTest {

    @Before fun propre() {
        PowerRun.tempNow = { 15f }
        PowerRun.log = {}
        PowerRun.clearAll()
    }

    /**
     * Pull en 3e (22,9 km/h pour 1 000 tr/min) de 1 500 à 4 300 tr/min à 1,2 m/s², 2 relevés par seconde, vitesse
     * entière comme l'OBD, puis passage en 4e (le régime chute) : une mesure doit sortir.
     */
    private fun pull(netLoad: Float, startNanos: Long = 1_000_000_000L) {
        var t = 0.0
        var n = startNanos
        // Quelques relevés avant, en roulant à charge moyenne (aucune mesure).
        repeat(3) { PowerRun.feed(1450, 33f, 40f, n); n += 500_000_000L }
        while (true) {
            val kmh = 34.35 + 1.2 * 3.6 * t
            val rpm = (kmh * 1000 / 22.9).roundToInt()
            if (rpm > 4300) break
            PowerRun.feed(rpm, kmh.roundToInt().toFloat(), netLoad, n)
            t += 0.5; n += 500_000_000L
        }
        // Passage en 4e : le régime tombe de plus de 300 tr/min.
        PowerRun.feed(3300, 99f, netLoad, n)
    }

    @Test fun accelerationAFondMesuree() {
        pull(netLoad = 98f)
        val r = PowerRun.state.value.last
        assertNotNull("pied au plancher dans un rapport : mesure", r)
        r!!
        // Physique attendue en haut du pull (≈ 27 m/s à 1,2 m/s²) : ≈ 95 ch ; la vitesse entière ajoute du bruit.
        val vTop = 98.0 / 3.6
        val attendu = PowerMath.engineCh(PowerMath.wheelForceN(1.2, vTop, 15f), vTop)
        assertTrue("pic ${r.peakCh} ch proche de ${attendu.roundToInt()} ch", kotlin.math.abs(r.peakCh - attendu) < attendu * 0.2)
        assertEquals("record = la seule mesure", r.peakCh, PowerRun.state.value.best!!.peakCh)
        assertTrue(!PowerRun.state.value.collecting)
    }

    @Test fun accelerationOrdinaireIgnoree() {
        // Charge utile 60 % : on accélère, mais pas à fond (cas courant en roulant).
        pull(netLoad = 60f)
        assertNull(PowerRun.state.value.last)
    }

    @Test fun deuxMesuresDeSuite() {
        pull(netLoad = 98f)
        val first = PowerRun.state.value.last!!.atMs
        Thread.sleep(5)
        pull(netLoad = 98f, startNanos = 100_000_000_000L)
        assertTrue("la 2e accélération est mesurée elle aussi", PowerRun.state.value.last!!.atMs > first)
    }

    @Test fun relevesInterrompus() {
        // Le boîtier OBD décroche au milieu : la mesure s'arrête, pas de point relié par-dessus le trou.
        PowerRun.feed(1500, 34f, 98f, 1_000_000_000L)
        PowerRun.feed(1530, 35f, 98f, 1_500_000_000L)
        PowerRun.feed(1560, 36f, 98f, 2_000_000_000L)
        assertTrue(PowerRun.state.value.collecting)
        PowerRun.feed(3000, 70f, 98f, 20_000_000_000L)
        assertTrue("trou de 18 s : mesure close", !PowerRun.state.value.collecting)
        assertNull(PowerRun.state.value.last)
    }
}
