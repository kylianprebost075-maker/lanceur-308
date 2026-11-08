package fr.p308.lanceur.drive

import android.content.Context
import android.content.SharedPreferences
import fr.p308.lanceur.vehicle.EngineState
import fr.p308.lanceur.vehicle.ShiftAdvice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Un trajet terminé (ou en cours) avec ce qu'il faut pour calculer le score de conduite souple. */
data class TripRecord(
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    val movingS: Double,
    val maxKmh: Float,
    val peakCh: Int,
    /** Accélérations et freinages brusques. */
    val harshAccel: Int,
    val harshBrake: Int,
    /** Secondes de roulage avec l'OBD connecté, dont avec le conseil « passe la vitesse » affiché. */
    val obdS: Int,
    val overRevS: Int,
    /** Secondes à plus de 2 800 tr/min moteur froid (eau sous 60 °C). */
    val coldRevS: Int,
) {
    val avgKmh: Double get() = if (movingS > 1) distanceKm * 3600 / movingS else 0.0
    val overRevPct: Int get() = if (obdS > 0) (overRevS * 100f / obdS).roundToInt() else 0
    val score: Int get() = TripLog.score(this)
}

/**
 * Historique des trajets. Un trajet commence dès 5 km/h et se termine après 5 minutes à l'arrêt (ou quand
 * l'autoradio se réveille après une pause plus longue). Gardé s'il fait au moins 500 m ; 100 trajets au plus.
 * Le trajet en cours est sauvegardé toutes les 30 s pour survivre à un arrêt de l'appli.
 */
object TripLog {
    private const val MAX_TRIPS = 100
    private const val END_AFTER_MS = 5 * 60_000L
    private const val MIN_KM = 0.5
    /** Seuils en km/h par seconde : ≈ 0,3 g en accélération, 0,37 g au freinage. Au-delà de 45 : saut de mesure. */
    private const val HARSH_ACCEL = 11f
    private const val HARSH_BRAKE = -13f
    private const val JUMP = 45f

    private var prefs: SharedPreferences? = null
    private val _history = MutableStateFlow<List<TripRecord>>(emptyList())
    val history: StateFlow<List<TripRecord>> = _history.asStateFlow()
    private val _current = MutableStateFlow<TripRecord?>(null)
    val current: StateFlow<TripRecord?> = _current.asStateFlow()

    /** Vrai en mode démo : rien n'est enregistré. */
    @Volatile
    var demo = false

    private var lastMoveMs = 0L
    private var lastFeedNanos = 0L
    private var lastKmh = 0f
    private var refKmh = 0f
    private var refNanos = 0L
    private var inAccel = false
    private var inBrake = false
    private var ticks = 0

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("trips", Context.MODE_PRIVATE)
        prefs = p
        _history.value = p.getString("list", null)?.let(::decodeList) ?: emptyList()
        // Trajet resté ouvert (appli arrêtée) : terminé s'il est ancien, repris sinon.
        p.getString("current", null)?.let(::decodeOne)?.let { cur ->
            if (System.currentTimeMillis() - cur.endMs > END_AFTER_MS) close(cur) else {
                _current.value = cur
                lastMoveMs = cur.endMs
            }
        }
    }

    /** Score sur 100 : brusqueries (40 pts), régime trop haut quand l'appli conseille de monter (30), moteur froid poussé (20). */
    fun score(t: TripRecord): Int {
        // Au moins 5 km pour la moyenne : un seul freinage sur un trajet très court ne doit pas tout faire chuter.
        val km = max(t.distanceKm, 5.0)
        val harshPer10 = (t.harshAccel + t.harshBrake) * 10 / km
        val penHarsh = minOf(40.0, harshPer10 * 5)
        val penRev = if (t.obdS >= 60) minOf(30.0, t.overRevS * 100.0 / t.obdS * 0.6) else 0.0
        val penCold = minOf(20.0, t.coldRevS / 3.0)
        return (100 - penHarsh - penRev - penCold).roundToInt().coerceIn(0, 100)
    }

    /** Vitesse (OBD ou GPS), appelée par [Speed.report]. */
    @Synchronized
    fun feed(kmh: Float, nanos: Long, nowMs: Long = System.currentTimeMillis()) {
        var t = _current.value
        if (t == null) {
            if (kmh < 5f) {
                lastFeedNanos = nanos
                lastKmh = kmh
                return
            }
            t = TripRecord(nowMs, nowMs, 0.0, 0.0, 0f, 0, 0, 0, 0, 0, 0)
            refNanos = 0L
            inAccel = false
            inBrake = false
        }
        val dt = if (lastFeedNanos == 0L) 0.0 else ((nanos - lastFeedNanos) / 1e9).coerceIn(0.0, 5.0)
        val avg = (kmh + lastKmh) / 2.0
        lastFeedNanos = nanos
        lastKmh = kmh
        if (kmh > 2f) lastMoveMs = nowMs

        var accel = t.harshAccel
        var brake = t.harshBrake
        if (refNanos == 0L) {
            refKmh = kmh; refNanos = nanos
        } else {
            val span = (nanos - refNanos) / 1e9f
            if (span >= 1f) {
                val a = (kmh - refKmh) / span
                if (span <= 3f && abs(a) < JUMP) {
                    if (a > HARSH_ACCEL) { if (!inAccel) accel++; inAccel = true } else if (a < HARSH_ACCEL / 2) inAccel = false
                    if (a < HARSH_BRAKE) { if (!inBrake) brake++; inBrake = true } else if (a > HARSH_BRAKE / 2) inBrake = false
                }
                refKmh = kmh; refNanos = nanos
            }
        }
        _current.value = t.copy(
            endMs = if (kmh > 2f) nowMs else t.endMs,
            distanceKm = t.distanceKm + avg / 3.6 * dt / 1000,
            movingS = t.movingS + if (avg > 2) dt else 0.0,
            maxKmh = max(t.maxKmh, kmh),
            harshAccel = accel, harshBrake = brake,
        )
    }

    /** Une fois par seconde (service) : régime, conseil de passage, moteur froid, fin du trajet. */
    @Synchronized
    fun tick(e: EngineState, nowMs: Long = System.currentTimeMillis()) {
        val t = _current.value ?: return
        if (lastMoveMs == 0L) lastMoveMs = t.endMs
        if (nowMs - lastMoveMs > END_AFTER_MS) {
            close(t)
            return
        }
        val moving = lastKmh > 5f
        _current.value = if (moving && e.connected && e.rpm != null) {
            t.copy(
                obdS = t.obdS + 1,
                overRevS = t.overRevS + if (e.advice.kind == ShiftAdvice.Kind.UP) 1 else 0,
                coldRevS = t.coldRevS + if ((e.coolantC ?: 90) < 60 && e.rpm > 2800) 1 else 0,
                peakCh = max(t.peakCh, e.powerCh ?: 0),
            )
        } else t
        if (++ticks % 30 == 0) saveCurrent()
    }

    /** Bouton « Terminer le trajet ». */
    @Synchronized
    fun finishNow() {
        _current.value?.let(::close)
    }

    /** Passage en mode démo ou retour : le trajet en cours est abandonné (données de démo jamais gardées). */
    @Synchronized
    fun discard() {
        _current.value = null
        lastMoveMs = 0L
        prefs?.edit()?.remove("current")?.apply()
    }

    @Synchronized
    fun delete(startMs: Long) {
        _history.value = _history.value.filterNot { it.startMs == startMs }
        prefs?.edit()?.putString("list", encodeList(_history.value))?.apply()
    }

    private fun close(t: TripRecord) {
        _current.value = null
        lastMoveMs = 0L
        prefs?.edit()?.remove("current")?.apply()
        if (demo || t.distanceKm < MIN_KM) return
        _history.value = (listOf(t) + _history.value).take(MAX_TRIPS)
        prefs?.edit()?.putString("list", encodeList(_history.value))?.apply()
    }

    private fun saveCurrent() {
        if (demo) return
        _current.value?.let { prefs?.edit()?.putString("current", encode(it).toString())?.apply() }
    }

    /** Exemple affiché en mode démo (jamais enregistré). */
    fun demoHistory(now: Long = System.currentTimeMillis()): List<TripRecord> {
        val h = 3_600_000L
        fun trip(ago: Long, km: Double, min: Int, maxK: Float, ch: Int, acc: Int, brk: Int, revPct: Int, cold: Int): TripRecord {
            val obd = min * 60
            return TripRecord(now - ago, now - ago + min * 60_000L, km, min * 60.0 * 0.85, maxK, ch, acc, brk, obd, obd * revPct / 100, cold)
        }
        return listOf(
            trip(2 * h, 18.4, 24, 112f, 88, 1, 0, 6, 0),
            trip(9 * h, 4.2, 11, 58f, 61, 2, 1, 22, 45),
            trip(26 * h, 18.1, 23, 118f, 92, 0, 1, 4, 0),
            trip(33 * h, 62.7, 48, 131f, 95, 3, 2, 12, 12),
            trip(50 * h, 7.9, 15, 74f, 70, 0, 0, 9, 0),
        )
    }

    private fun encode(t: TripRecord) = JSONObject()
        .put("s", t.startMs).put("e", t.endMs).put("km", t.distanceKm).put("mv", t.movingS)
        .put("max", t.maxKmh.toDouble()).put("ch", t.peakCh).put("acc", t.harshAccel).put("frein", t.harshBrake)
        .put("obd", t.obdS).put("haut", t.overRevS).put("froid", t.coldRevS)

    private fun decode(o: JSONObject) = TripRecord(
        o.getLong("s"), o.getLong("e"), o.getDouble("km"), o.getDouble("mv"), o.getDouble("max").toFloat(),
        o.optInt("ch"), o.optInt("acc"), o.optInt("frein"), o.optInt("obd"), o.optInt("haut"), o.optInt("froid"),
    )

    private fun encodeList(list: List<TripRecord>) = JSONArray(list.map(::encode)).toString()

    private fun decodeList(s: String): List<TripRecord> =
        runCatching { JSONArray(s).let { a -> List(a.length()) { decode(a.getJSONObject(it)) } } }.getOrDefault(emptyList())

    private fun decodeOne(s: String): TripRecord? = runCatching { decode(JSONObject(s)) }.getOrNull()
}
