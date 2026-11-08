package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Étagement de la boîte appris sur la route. Les valeurs de départ (ShiftAdvice.KMH_PER_1000) sont approchées :
 * pneus, pont ou boîte un peu différents suffisent pour que le rapport ne soit plus reconnu. Ici, chaque fois que
 * la voiture roule à rapport stable (vitesse / régime constant sur 3 relevés), le rapport km/h pour 1 000 tr/min est
 * compté ; les pics du compte sont les vrais rapports de la boîte, rangés dans l'ordre en suivant l'étagement de départ.
 */
object GearCalib {
    private const val BINS = 130
    private val LN_MIN = ln(5.0)
    private val LN_SPAN = ln(70.0) - LN_MIN
    /** Relevés à rapport stable avant le premier calcul. */
    private const val MIN_TOTAL = 60

    private var prefs: SharedPreferences? = null
    private val counts = IntArray(BINS)
    private var total = 0
    private var sinceCompute = 0
    private var prevRatio = 0f
    private var stable = 0

    /** km/h à 1 000 tr/min appris, 1re → 5e, ou null tant qu'il n'y a pas assez de relevés. */
    @Volatile
    var learned: FloatArray? = null
        private set

    val samples: Int get() = total

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("gears", Context.MODE_PRIVATE)
        prefs = p
        synchronized(this) {
            counts.fill(0)
            p.getString("bins", null)?.split(',')?.forEach { e ->
                val (b, c) = e.split(':').takeIf { it.size == 2 } ?: return@forEach
                val i = b.toIntOrNull() ?: return@forEach
                if (i in 0 until BINS) counts[i] = c.toIntOrNull() ?: 0
            }
            total = counts.sum()
            learned = compute()
        }
    }

    /** Régime et vitesse lus au même tour de la boucle OBD (jamais en mode démo). */
    @Synchronized
    fun feed(rpm: Int, kmh: Int) {
        if (kmh < 12 || rpm < 1000) {
            stable = 0
            prevRatio = 0f
            return
        }
        val r = kmh * 1000f / rpm
        stable = if (prevRatio > 0f && abs(r / prevRatio - 1f) < 0.03f) stable + 1 else 0
        prevRatio = r
        if (stable < 2) return
        val b = bin(r.toDouble()) ?: return
        counts[b]++
        total++
        if (total > 20_000) { // les relevés anciens pèsent de moins en moins
            for (i in counts.indices) counts[i] /= 2
            total = counts.sum()
        }
        if (++sinceCompute >= 40) {
            sinceCompute = 0
            learned = compute()
            save()
        }
    }

    @Synchronized
    fun reset() {
        counts.fill(0)
        total = 0
        sinceCompute = 0
        learned = null
        save()
    }

    fun status(): String = when {
        learned != null -> "Rapports appris sur la route (${total} relevés)"
        total == 0 -> "Rapports : valeurs approchées, apprentissage en roulant"
        else -> "Rapports : apprentissage en cours ($total relevés)"
    }

    private fun bin(r: Double): Int? = ((ln(r) - LN_MIN) / LN_SPAN * BINS).toInt().takeIf { r > 0 && it in 0 until BINS }

    private fun lnCenter(b: Double) = LN_MIN + (b + 0.5) * LN_SPAN / BINS

    private fun save() {
        val s = counts.withIndex().filter { it.value > 0 }.joinToString(",") { "${it.index}:${it.value}" }
        prefs?.edit()?.putString("bins", s)?.apply()
    }

    /** Pics du compte, mis en face des rapports de départ (même écart entre rapports), ou null si c'est ambigu. */
    private fun compute(): FloatArray? = learnFrom(counts, total)

    internal fun learnFrom(counts: IntArray, total: Int): FloatArray? {
        if (total < MIN_TOTAL) return null
        val smooth = IntArray(BINS) { b -> (b - 1..b + 1).sumOf { counts.getOrElse(it) { 0 } } }
        val thr = maxOf(20, (total * 0.03).toInt())
        // Pics : maximum local au-dessus du seuil, position affinée par moyenne pondérée sur ±2 cases.
        var peaks = mutableListOf<Pair<Double, Int>>() // (ln du rapport, poids)
        for (b in 0 until BINS) {
            val s = smooth[b]
            if (s < thr || s < smooth.getOrElse(b - 1) { 0 } || s <= smooth.getOrElse(b + 1) { 0 }) continue
            var w = 0.0
            var sum = 0.0
            for (i in b - 2..b + 2) {
                val c = counts.getOrElse(i) { 0 }
                w += c
                sum += c * lnCenter(i.toDouble())
            }
            if (w > 0) peaks += (sum / w) to s
        }
        // Deux pics à moins de 15 % l'un de l'autre sont le même rapport.
        val merged = mutableListOf<Pair<Double, Int>>()
        for (p in peaks.sortedByDescending { it.second }) {
            if (merged.none { abs(it.first - p.first) < 0.14 }) merged += p
        }
        peaks = merged.take(5).sortedBy { it.first }.toMutableList()
        if (peaks.isEmpty()) return null

        val def = ShiftAdvice.KMH_PER_1000.map { ln(it.toDouble()) }
        val k = peaks.size
        var best: Pair<Int, Double>? = null // (décalage, écart moyen)
        var bestCost = Double.MAX_VALUE
        for (o in 0..def.size - k) {
            val resid = peaks.indices.map { peaks[it].first - def[it + o] }
            val m = resid.average()
            val cost = resid.sumOf { (it - m) * (it - m) } + 0.1 * m * m
            if (cost < bestCost) { bestCost = cost; best = o to m }
        }
        val (o, m) = best ?: return null
        // Un seul rapport vu, loin des valeurs de départ : impossible de savoir lequel c'est.
        if (k == 1 && abs(m) > 0.08) return null
        val out = FloatArray(def.size) { g ->
            val i = g - o
            if (i in peaks.indices) exp(peaks[i].first).toFloat() else exp(def[g] + m).toFloat()
        }
        // Garde-fou : rapports croissants et nettement séparés, sinon on garde les valeurs de départ.
        for (g in 1 until out.size) if (out[g] < out[g - 1] * 1.15f) return null
        return out
    }
}
