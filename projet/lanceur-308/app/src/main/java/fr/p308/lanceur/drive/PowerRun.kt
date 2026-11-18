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
        // Regroupement par tranches de 200 tr/min : une courbe lisse malgré le bruit point à point.
        val points = raw.groupBy { it.rpm / 200 }
            .map { (bin, g) -> PowerPoint(bin * 200 + 100, g.map { it.ch }.average().toFloat(), g.map { it.nm }.average().toFloat()) }
            .sortedBy { it.rpm }
        if (points.size < 3) return null
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
    val status: String = "Accélère à fond en 3e ou 4e sur route plate",
)

/**
 * Banc de puissance par accélération (v0.46), nourri comme le chrono par la vitesse (OBD ou GPS). Il repère tout seul
 * une accélération à fond dans un seul rapport (charge forte, régime et vitesse qui montent), relève vitesse + régime
 * tout du long, puis calcule la courbe à la fin. Rien n'est envoyé à la voiture : lecture seule.
 */
object PowerRun {
    private const val FULL_LOAD = 70        // charge moteur (%) au-dessus de laquelle on considère « pied au plancher »
    private const val SHIFT_DROP = 300      // chute de régime (tr/min) = passage de rapport : fin de la mesure
    private const val MIN_POINTS = 6

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(PowerState())
    val state: StateFlow<PowerState> = _state.asStateFlow()

    private val samples = ArrayList<Triple<Long, Float, Int>>()
    private var active = false
    private var lastKmh = -1f
    private var lastRpm = 0
    private var armed = true

    /** Fournis par l'app au moment de calculer (évite de dépendre d'Android dans le calcul). */
    var rpmNow: () -> Int? = { null }
    var loadNow: () -> Int? = { null }
    var tempNow: () -> Float? = { null }

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("power", Context.MODE_PRIVATE)
        prefs = p
        rpmNow = { fr.p308.lanceur.vehicle.Engine.state.value.rpm }
        loadNow = { fr.p308.lanceur.vehicle.Engine.state.value.loadPct }
        tempNow = { fr.p308.lanceur.vehicle.Vehicle.state.value.outsideTempC }
        val best = decode(p.getString("best", null))
        _state.value = PowerState(last = decode(p.getString("last", null)), best = best)
    }

    /** Prépare une nouvelle mesure : la prochaine accélération à fond sera relevée (le record est gardé). */
    fun arm() {
        armed = true
        active = false
        samples.clear()
        _state.value = _state.value.copy(collecting = false, status = "Prêt : accélère à fond en 3e ou 4e sur route plate")
    }

    /** Efface le record (et la dernière mesure). */
    fun resetBest() {
        prefs?.edit()?.remove("best")?.remove("last")?.apply()
        _state.value = PowerState()
    }

    @Synchronized
    fun feed(kmh: Float, nanos: Long) {
        val rpm = rpmNow() ?: return
        val load = loadNow() ?: 0
        val ms = nanos / 1_000_000
        val rising = kmh > lastKmh + 0.2f
        val pulling = load >= FULL_LOAD && rpm > 1000

        if (!active) {
            if (armed && pulling && rising && rpm in 1100..4800) {
                active = true
                samples.clear()
                samples += Triple(ms, kmh, rpm)
                _state.value = _state.value.copy(collecting = true, status = "Mesure en cours…")
            }
        } else {
            val shifted = rpm < lastRpm - SHIFT_DROP
            if (pulling && !shifted && kmh >= lastKmh - 1f && rpm <= 5200) {
                samples += Triple(ms, kmh, rpm)
            } else {
                finish()
            }
        }
        lastKmh = kmh
        lastRpm = rpm
    }

    private fun finish() {
        val result = PowerMath.curve(samples.toList(), tempNow())
        active = false
        samples.clear()
        if (result == null) {
            _state.value = _state.value.copy(collecting = false, status = "Mesure trop courte : recommence, pied au plancher jusqu'à la zone rouge")
            return
        }
        armed = false
        val best = _state.value.best
        val newBest = if (best == null || result.peakCh > best.peakCh) result else best
        _state.value = PowerState(last = result, best = newBest, collecting = false,
            status = "Mesure : ${result.peakCh} ch à ${result.peakChRpm} tr/min, ${result.peakNm} Nm")
        prefs?.edit()?.putString("last", encode(result))?.putString("best", encode(newBest))?.apply()
        fr.p308.lanceur.vehicle.ObdLog.log("PUISSANCE", "banc : %d ch à %d tr/min, %d Nm à %d tr/min (%d points)"
            .format(result.peakCh, result.peakChRpm, result.peakNm, result.peakNmRpm, result.points.size))
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
