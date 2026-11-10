package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/** Rapport engagé, conseil de passage et apprentissage de l'étagement : vérifiés sans la voiture. */
class GearTest {
    /** Même découpage que GearCalib (130 cases entre 5 et 70 km/h pour 1 000 tr/min, échelle logarithmique). */
    private fun bin(ratio: Double): Int = ((ln(ratio) - ln(5.0)) / (ln(70.0) - ln(5.0)) * 130).toInt()

    private fun counts(vararg ratios: Pair<Double, Int>): IntArray =
        IntArray(130).also { c -> ratios.forEach { (r, n) -> c[bin(r)] += n } }

    private fun assertNear(expected: Double, actual: Float, tolerancePct: Double) {
        val gap = abs(actual - expected) / expected * 100
        if (gap > tolerancePct) throw AssertionError("attendu ≈ $expected, obtenu $actual (écart ${"%.1f".format(gap)} %)")
    }

    // --- Rapport engagé (étagement de départ, rien d'appris) ---

    @Test
    fun `rapport reconnu a partir du regime et de la vitesse`() {
        assertEquals(1, ShiftAdvice.gear(2000, 16))
        assertEquals(2, ShiftAdvice.gear(2000, 29))
        assertEquals(3, ShiftAdvice.gear(2000, 46))
        assertEquals(4, ShiftAdvice.gear(2000, 63))
        assertEquals(5, ShiftAdvice.gear(2000, 81))
    }

    @Test
    fun `pas de rapport a l'arret, au point mort ou entre deux rapports`() {
        assertNull(ShiftAdvice.gear(null, 50))
        assertNull(ShiftAdvice.gear(2000, null))
        assertNull(ShiftAdvice.gear(800, 3))
        assertNull(ShiftAdvice.gear(500, 30))
        assertNull(ShiftAdvice.gear(2000, 37)) // 18,5 km/h pour 1 000 tr/min : entre la 2e et la 3e
    }

    @Test
    fun `conseil de monter un rapport`() {
        val a = ShiftAdvice.advise(2500, 57, 30, 90)
        assertEquals(ShiftAdvice.Kind.UP, a.kind)
        assertEquals(3, a.gear)
        assertEquals(4, a.target)
    }

    @Test
    fun `pas de conseil de monter si le moteur tomberait trop bas ou en 5e`() {
        // Forte charge sous 3 800 tr/min : pas de conseil ; au-delà de 3 800 tr/min : conseillé de toute façon.
        assertEquals(ShiftAdvice.Kind.NONE, ShiftAdvice.advise(2500, 57, 85, 90).kind)
        assertEquals(ShiftAdvice.Kind.UP, ShiftAdvice.advise(3900, 89, 85, 90).kind)
        assertEquals(ShiftAdvice.Kind.NONE, ShiftAdvice.advise(2500, 101, 30, 90).kind) // déjà en 5e
    }

    @Test
    fun `conseil de retrograder quand le moteur peine`() {
        val a = ShiftAdvice.advise(1100, 45, 80, 90)
        assertEquals(ShiftAdvice.Kind.DOWN, a.kind)
        assertEquals(5, a.gear)
        assertEquals(4, a.target)
        assertEquals(ShiftAdvice.Kind.NONE, ShiftAdvice.advise(1100, 45, 30, 90).kind) // faible charge : laissé tranquille
    }

    @Test
    fun `moteur froid`() {
        assertEquals(ShiftAdvice.Kind.COLD, ShiftAdvice.advise(3000, 68, 30, 40).kind)
        assertEquals(ShiftAdvice.Kind.COLD, ShiftAdvice.advise(1800, 41, 30, 40).kind)
        assertEquals(ShiftAdvice.Kind.NONE, ShiftAdvice.advise(1800, 41, 30, 90).kind)
    }

    // --- Apprentissage de l'étagement ---

    @Test
    fun `pas assez de releves, rien d'appris`() {
        assertNull(GearCalib.learnFrom(counts(22.9 to 30), 30))
    }

    @Test
    fun `boite 5 pour cent plus longue apprise sur les cinq rapports`() {
        val real = doubleArrayOf(8.3, 15.3, 24.0, 33.2, 42.6)
        val c = counts(*real.map { it to 100 }.toTypedArray())
        val learned = GearCalib.learnFrom(c, 500)
        assertNotNull(learned)
        real.forEachIndexed { i, r -> assertNear(r, learned!![i], 2.5) }
    }

    @Test
    fun `trois rapports vus seulement, les autres sont deduits`() {
        val c = counts(24.0 to 100, 33.2 to 100, 42.6 to 100)
        val learned = GearCalib.learnFrom(c, 300)
        assertNotNull(learned)
        assertNear(24.0, learned!![2], 2.5)
        assertNear(33.2, learned[3], 2.5)
        assertNear(42.6, learned[4], 2.5)
        assertNear(7.9 * 1.05, learned[0], 4.0)
        assertNear(14.6 * 1.05, learned[1], 4.0)
    }

    @Test
    fun `un seul rapport loin de tout, on ne devine pas`() {
        assertNull(GearCalib.learnFrom(counts(18.5 to 200), 200))
    }

    @Test
    fun `un seul rapport proche d'une valeur de depart, accepte`() {
        val learned = GearCalib.learnFrom(counts(23.3 to 200), 200)
        assertNotNull(learned)
        assertNear(23.3, learned!![2], 2.5)
    }

    @Test
    fun `releves parasites sous le seuil ignores`() {
        val c = counts(8.3 to 100, 15.3 to 100, 24.0 to 100, 33.2 to 100, 42.6 to 100, 19.0 to 8, 55.0 to 6)
        val learned = GearCalib.learnFrom(c, 514)
        assertNotNull(learned)
        assertNear(24.0, learned!![2], 2.5)
        assertNear(42.6, learned[4], 2.5)
    }
}
