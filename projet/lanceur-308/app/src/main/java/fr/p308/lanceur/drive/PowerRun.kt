package fr.p308.lanceur.drive

import android.content.Context
import android.content.SharedPreferences
import fr.p308.lanceur.vehicle.CarProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Un point de la courbe mesurée : régime, puissance (ch) et couple (N·m) estimés à ce régime. */
data class PowerPoint(val rpm: Int, val ch: Float, val nm: Float)

/** Résultat d'une mesure : la courbe (par régime croissant) et les deux pics, avec le régime où ils tombent. */
data class PowerResult(
    val points: List<PowerPoint>,
    val peakCh: Int,
    val peakChRpm: Int,
    val peakNm: Int,
    val peakNmRpm: Int,
    val atMs: Long,
)

/**
 * Calcul PUR de la puissance d'après une accélération (aucun appel Android) : vérifié par PowerMathTest.
 *
 * Principe : en accélérant à fond, le moteur fournit aux roues une force qui (1) accélère la voiture, (2) vainc l'air,
 * (3) vainc le roulement. On remonte de l'accélération mesurée (dérivée de la vitesse) à cette force, puis à la
 * puissance (force × vitesse) et au couple. La route est supposée plate : à faire sur du plat, sans vent.
 */
object PowerMath {
    private const val G = 9.81
    private const val W_PER_CH = 735.5

    /** Masse d'air (kg/m³) selon la température, au niveau de la mer (l'altitude n'est pas connue). */
    fun airDensity(tempC: Float?): Double = 1.225 * 288.15 / (273.15 + (tempC ?: 15f))

    /** Force aux roues (N) pour accélérer à [aMs2] à la vitesse [vMs], air à [tempC]. */
    fun wheelForceN(aMs2: Double, vMs: Double, tempC: Float?): Double {
        val inertia = CarProfile.MASS_KG * CarProfile.ROTATING_MASS * aMs2
        val aero = 0.5 * airDensity(tempC) * CarProfile.CDA * vMs * vMs
        val roll = CarProfile.ROLL_RESIST * CarProfile.MASS_KG * G
        return inertia + aero + roll
    }

    /** Puissance moteur (ch) pour une force [forceN] aux roues à la vitesse [vMs]. */
    fun engineCh(forceN: Double, vMs: Double): Double = forceN * vMs / CarProfile.DRIVETRAIN_EFF / W_PER_CH

    /** Couple moteur (N·m) pour une puissance [ch] au régime [rpm]. */
    fun torqueNm(ch: Double, rpm: Int): Double =
        if (rpm <= 0) 0.0 else ch * W_PER_CH / (rpm * 2 * Math.PI / 60.0)

    /** Moyenne glissante sur 3 points (adoucit le bruit de la vitesse avant d'en prendre la dérivée). */
    internal fun smooth(v: List<Double>): List<Double> = v.indices.map { i ->
        when (i) {
            0, v.size - 1 -> v[i]
            else -> (v[i - 1] + v[i] + v[i + 1]) / 3
        }
    }

    /**
     * Courbe de puissance d'après les points d'une accélération (temps en ms, vitesse en km/h, régime).
     * Renvoie null si la mesure est trop courte ou trop bruitée pour être exploitable.
     */
    fun curve(samples: List<Triple<Long, Float, Int>>, tempC: Float?, nowMs: Long = System.currentTimeMillis()): PowerResult? {
        if (samples.size < 6) return null
        val ts = samples.map { it.first / 1000.0 }
        val vs = smooth(samples.map { it.second / 3.6 })
        val rpms = samples.map { it.third }
        // Puissance et couple à chaque point intérieur (dérivée centrée de la vitesse).
        val raw = ArrayList<PowerPoint>()
        for (i in 1 until samples.size - 1) {
            val dt = ts[i + 1] - ts[i - 1]
            if (dt <= 0.05) continue
            val a = (vs[i + 1] - vs[i - 1]) / dt
            if (a <= 0.1 || vs[i] < 3) continue
            val ch = engineCh(wheelForceN(a, vs[i], tempC), vs[i])
            if (ch <= 0 || ch > 400) continue
            raw += PowerPoint(rpms[i], ch.toFloat(), torqueNm(ch, rpms[i]).toFloat())
        }
        if (raw.size < 4) return null
        if ((raw.maxOf { it.rpm } - raw.minOf { it.rpm }) < 900) return null
        // Regroupement par tranches de 200 tr/min, puis moyenne avec les tranches voisines (v0.47) : la vitesse OBD est
        // au km/h près, et sans ce lissage le pic serait pris sur la tranche la plus bruitée, donc surestimé.
        val bins = raw.groupBy { it.rpm / 200 }
            .map { (bin, g) -> PowerPoint(bin * 200 + 100, g.map { it.ch }.average().toFloat(), g.map { it.nm }.average().toFloat()) }
            .sortedBy { it.rpm }
        if (bins.size < 3) return null
        val ch = smooth(bins.map { it.ch.toDouble() })
        val nm = smooth(bins.map { it.nm.toDouble() })
        val points = bins.indices.map { PowerPoint(bins[it].rpm, ch[it].toFloat(), nm[it].toFloat()) }
        val pCh = points.maxBy { it.ch }
        val pNm = points.maxBy { it.nm }
        return PowerResult(points, pCh.ch.roundToInt(), pCh.rpm, pNm.nm.roundToInt(), pNm.rpm, nowMs)
    }
}

/** État affiché par l'écran du banc de puissance. */
data class PowerState(
    val last: PowerResult? = null,
    val best: PowerResult? = null,
    val collecting: Boolean = false,
    val status: String = PowerRun.IDLE_STATUS,
)

/**
 * Banc de puissance par accélération (v0.46). Nourri par la boucle OBD à chaque tour où régime, vitesse et charge
 * ont été lus ensemble (v0.47 : avant, la vitesse arrivait avec le régime du tour précédent). Chaque accélération pied
 * au plancher dans un seul rapport est mesurée toute seule : dernière mesure et record. Lecture seule.
 * La logique est vérifiée par PowerRunTest (sans Android : préférences et journal facultatifs).
 */
object PowerRun {
    /**
     * Charge UTILE (ralenti retiré, voir netLoadPct) au-dessus de laquelle on est « pied au plancher ». v0.47 : la
     * 0.46 prenait 70 % de charge brute, or ce calculateur lit déjà 60-70 % en roulant normalement (journal du 18/11) :
     * une accélération ordinaire passait pour une mesure.
     */
    const val FULL_LOAD = 85f
    /** Chute de régime (tr/min) = passage de rapport : fin de la mesure. */
    private const val SHIFT_DROP = 300
    /** Trou entre deux relevés (boîtier OBD qui décroche) : la mesure s'arrête là. */
    private const val MAX_GAP_MS = 2_500L
    const val IDLE_STATUS = "Mesure automatique : accélère à fond en 3e ou 4e sur route plate"

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(PowerState())
    val state: StateFlow<PowerState> = _state.asStateFlow()

    private val samples = ArrayList<Triple<Long, Float, Int>>()
    private var active = false
    private var lastKmh = -1f
    private var lastRpm = 0
    private var lastMs = 0L

    /** Température extérieure (densité de l'air) et journal : branchés par init, absents dans les tests. */
    var tempNow: () -> Float? = { null }
    var log: (String) -> Unit = {}

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("power", Context.MODE_PRIVATE)
        prefs = p
        tempNow = { fr.p308.lanceur.vehicle.Vehicle.state.value.outsideTempC }
        log = { fr.p308.lanceur.vehicle.ObdLog.log("PUISSANCE", it) }
        _state.value = PowerState(last = decode(p.getString("last", null)), best = decode(p.getString("best", null)))
    }

    /** Efface la dernière mesure et le record. */
    @Synchronized
    fun clearAll() {
        prefs?.edit()?.remove("best")?.remove("last")?.apply()
        samples.clear()
        active = false
        _state.value = PowerState()
    }

    /**
     * Un tour de la boucle OBD : régime, vitesse (km/h) et charge utile (%) lus ensemble. Commence une mesure quand
     * le pied est au plancher et que la vitesse monte ; la termine au passage de rapport, au lever de pied, ou si les
     * relevés s'interrompent.
     */
    @Synchronized
    fun feed(rpm: Int, kmh: Float, netLoadPct: Float, nanos: Long) {
        val ms = nanos / 1_000_000
        val gap = lastMs != 0L && ms - lastMs > MAX_GAP_MS
        if (active && gap) finish()
        val pulling = netLoadPct >= FULL_LOAD && rpm > 1000
        if (!active) {
            val rising = lastKmh >= 0f && !gap && kmh > lastKmh + 0.2f
            if (pulling && rising && rpm in 1100..4800) {
                active = true
                samples.clear()
                samples += Triple(ms, kmh, rpm)
                _state.value = _state.value.copy(collecting = true, status = "Mesure en cours…")
            }
        } else {
            val shifted = rpm < lastRpm - SHIFT_DROP
            if (pulling && !shifted && kmh >= lastKmh - 1f && rpm <= 5200) samples += Triple(ms, kmh, rpm) else finish()
        }
        lastKmh = kmh
        lastRpm = rpm
        lastMs = ms
    }

    private fun finish() {
        val taken = samples.toList()
        active = false
        samples.clear()
        val result = PowerMath.curve(taken, tempNow())
        if (result == null) {
            // Un coup d'accélérateur bref (1 ou 2 relevés) ne mérite pas de message.
            val status = if (taken.size >= 3) "Mesure trop courte : garde le pied au plancher jusqu'à la zone rouge, sans changer de rapport"
            else _state.value.status.takeIf { it != "Mesure en cours…" } ?: IDLE_STATUS
            _state.value = _state.value.copy(collecting = false, status = status)
            return
        }
        val best = _state.value.best
        val newBest = if (best == null || result.peakCh > best.peakCh) result else best
        _state.value = PowerState(last = result, best = newBest, collecting = false,
            status = "Mesure : ${result.peakCh} ch à ${result.peakChRpm} tr/min, ${result.peakNm} Nm")
        prefs?.edit()?.putString("last", encode(result))?.putString("best", encode(newBest))?.apply()
        log("banc : %d ch à %d tr/min, %d Nm à %d tr/min (%d relevés, %d points)"
            .format(result.peakCh, result.peakChRpm, result.peakNm, result.peakNmRpm, taken.size, result.points.size))
    }

    private fun encode(r: PowerResult?): String? = r?.let {
        JSONObject().put("ch", it.peakCh).put("chRpm", it.peakChRpm).put("nm", it.peakNm).put("nmRpm", it.peakNmRpm)
            .put("ms", it.atMs)
            .put("pts", JSONArray().apply { it.points.forEach { p -> put(JSONObject().put("r", p.rpm).put("c", p.ch.toDouble()).put("n", p.nm.toDouble())) } })
            .toString()
    }

    private fun decode(s: String?): PowerResult? = s?.let {
        runCatching {
            val o = JSONObject(it)
            val a = o.getJSONArray("pts")
            val pts = List(a.length()) { i -> a.getJSONObject(i).let { p -> PowerPoint(p.getInt("r"), p.getDouble("c").toFloat(), p.getDouble("n").toFloat()) } }
            PowerResult(pts, o.getInt("ch"), o.getInt("chRpm"), o.getInt("nm"), o.getInt("nmRpm"), o.optLong("ms"))
        }.getOrNull()
    }
}
