package fr.p308.lanceur.vehicle

import android.content.Context
import kotlin.math.PI
import kotlin.math.roundToInt

// v0.49 : sorti de Engine.kt (sans changement) : Ã©tats, dÃ©codage des codes dÃ©faut, couple et puissance estimÃ©s,
// charge de ralenti. Tout ici est calculÃ© sans Bluetooth et vÃ©rifiÃ© par les tests.

data class EngineState(
    val status: String = "Adaptateur OBD non connecté",
    val connected: Boolean = false,
    val rpm: Int? = null,
    val loadPct: Int? = null,
    val speedKmh: Int? = null,
    val coolantC: Int? = null,
    val boostBar: Float? = null,
    /** Tension mesurée par le calculateur (PID 42) ou par le boîtier Vgate (ATRV), utile pour l'alerte batterie. */
    val voltage: Float? = null,
    /** Débit d'air (PID 10, g/s) : sert à repérer les régénérations du FAP (vanne EGR fermée → plus d'air frais). */
    val mafGs: Float? = null,
    val torqueNm: Int? = null,
    val powerCh: Int? = null,
    val peakCh: Int = 0,
    /** Rapport estimé et conseil de passage (calculés, voir ShiftAdvice). */
    val advice: ShiftAdvice.Advice = ShiftAdvice.Advice(null, ShiftAdvice.Kind.NONE),
)

/** Résultat de la dernière lecture des codes défaut (onglet Moteur → Diagnostic). */
data class DiagState(
    val busy: Boolean = false,
    val status: String = "Pas encore lu",
    val readAtMs: Long? = null,
    val stored: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val permanent: List<String> = emptyList(),
    /** Kilométrage lu dans le calculateur moteur (EDC16C34, identifiant 21 10). */
    val ecuKm: Int? = null,
)

/** Codes défaut d'une réponse aux modes 03 / 07 / 0A (en-têtes masqués, trames « 0: … » comprises). */
internal fun parseDtcs(resp: String, modeByte: Int): List<String> {
    val hex = buildString {
        for (raw in resp.uppercase().split('\r', '\n')) {
            var t = raw.replace(" ", "").trim()
            if (t.isEmpty()) continue
            if (Regex("^[0-9A-F]:").containsMatchIn(t)) t = t.substring(2)
            else if (Regex("^[0-9A-F]{3}$").matches(t)) continue // longueur totale d'une réponse multi-trames
            if (!Regex("^[0-9A-F]+$").matches(t)) continue
            append(t)
        }
    }
    val bytes = hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
    val start = bytes.indexOf(modeByte)
    if (start < 0) return emptyList()
    val payload = bytes.drop(start)
    val pairs = if (payload.size % 2 == 0) payload.drop(2) else payload.drop(1)
    val codes = mutableListOf<String>()
    var i = 0
    while (i + 1 < pairs.size) {
        val a = pairs[i]
        val b = pairs[i + 1]
        if (a != 0 || b != 0) codes += "PCBU"[a shr 6] + "${(a shr 4) and 3}" + "%X".format(a and 0xF) + "%02X".format(b)
        i += 2
    }
    return codes.distinct()
}

/** Couple maxi selon le régime : courbe du profil de la voiture (CarProfile.kt). */
private val TORQUE_CURVE = CarProfile.TORQUE_CURVE

fun maxTorqueNm(rpm: Int): Float {
    if (rpm <= TORQUE_CURVE.first().first) return TORQUE_CURVE.first().second.toFloat()
    if (rpm >= TORQUE_CURVE.last().first) return TORQUE_CURVE.last().second.toFloat()
    val (r1, t1) = TORQUE_CURVE.last { it.first <= rpm }
    val (r2, t2) = TORQUE_CURVE.first { it.first > rpm }
    return t1 + (t2 - t1) * (rpm - r1).toFloat() / (r2 - r1)
}

/**
 * v0.42 : charge utile. La charge calculée (PID 04) comprend les frottements et les accessoires : au ralenti moteur
 * chaud elle vaut déjà ≈ 35 % sur l'EDC16C34 (journaux du 9 au 16/11 : 35, 36, 36 % entre 750 et 825 tr/min) alors
 * que le moteur ne fournit rien aux roues. Le couple utile est compté au-dessus de cette charge de ralenti :
 * (charge − ralenti) / (100 − ralenti), ce qui laisse 100 % à pleine charge. Sans cela, la puissance était surestimée
 * à charge partielle (d'où 9,8 L/100 estimés pour 5,5 réels). [idleLoadPct] = 0 : pas encore apprise, pas de correction.
 */
fun netLoadPct(loadPct: Int, idleLoadPct: Float): Float =
    if (idleLoadPct <= 0f) loadPct.toFloat().coerceIn(0f, 100f)
    else ((loadPct - idleLoadPct) / (100f - idleLoadPct) * 100f).coerceIn(0f, 100f)

/** Puissance estimée : charge utile (PID 04 moins la charge de ralenti) × couple maxi au régime courant. */
fun estimate(rpm: Int, loadPct: Int, idleLoadPct: Float = 0f): Pair<Int, Int> {
    val torque = maxTorqueNm(rpm) * netLoadPct(loadPct, idleLoadPct) / 100f
    val kw = torque * rpm * 2 * PI / 60_000
    return torque.roundToInt() to (kw * 1.35962).roundToInt()
}

/**
 * v0.42 : charge lue au ralenti, apprise toute seule (voiture à l'arrêt, 650 à 950 tr/min, eau à 60 °C ou plus) :
 * moyenne des 60 premiers relevés, puis suivi lent. Bornée à [MAX_PCT] : au-delà, la lecture n'est pas un ralenti.
 * Gardée dans les préférences « vehicle » (donc sauvegardée). Calcul pur vérifié par IdleLoadTest.
 */
object IdleLoad {
    const val MIN_SAMPLES = 60
    const val MAX_PCT = 45f
    private var prefs: android.content.SharedPreferences? = null
    private var sum = 0.0
    private var n = 0
    private var sinceSave = 0

    /** 0 tant qu'elle n'est pas apprise. */
    @Volatile
    var pct: Float = 0f
        private set

    @Synchronized
    fun init(ctx: Context?) {
        val p = ctx?.getSharedPreferences("vehicle", Context.MODE_PRIVATE)
        prefs = p
        val stored = (p?.getFloat("idleLoadPct", 0f) ?: 0f).coerceIn(0f, MAX_PCT)
        // Appelé à chaque (re)lancement de la boucle OBD : l'apprentissage en cours n'est remis à zéro que si la
        // valeur gardée a changé (restauration d'une sauvegarde).
        if (stored != pct) { sum = 0.0; n = 0 }
        pct = stored
    }

    /** Un relevé de la boucle OBD. Renvoie vrai au moment où la charge de ralenti vient d'être apprise. */
    @Synchronized
    fun feed(rpm: Int, loadPct: Int, kmh: Int?, coolantC: Int?): Boolean {
        if (kmh != 0 || rpm !in 650..950 || (coolantC ?: 0) < 60 || loadPct !in 5..60) return false
        if (pct > 0f) {
            pct = (pct + (loadPct - pct) * 0.005f).coerceIn(1f, MAX_PCT)
            if (++sinceSave >= 200) { sinceSave = 0; save() }
            return false
        }
        sum += loadPct
        if (++n < MIN_SAMPLES) return false
        pct = (sum / n).toFloat().coerceIn(1f, MAX_PCT)
        save()
        return true
    }

    private fun save() {
        prefs?.edit()?.putFloat("idleLoadPct", pct)?.apply()
    }
}
