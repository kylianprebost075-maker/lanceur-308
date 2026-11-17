package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class CrankVerdict(val label: String) { GOOD("Bonne"), FAIR("À surveiller"), WEAK("Faible") }

/**
 * Un démarrage mesuré : tension au repos (contact mis), tension la plus basse au démarreur (null si la chute
 * n'a pas pu être saisie, par exemple connexion coupée pendant le démarreur) et tension de charge juste après
 * (alternateur).
 */
data class CrankRecord(
    val atMs: Long,
    val restV: Float,
    val minV: Float?,
    val coolantC: Int?,
    val chargeV: Float? = null,
    /** v0.38 : température extérieure au démarrage (null si inconnue). */
    val outsideC: Float? = null,
    /** v0.38 : « CAN » si mesuré par le boîtier CAN de l'autoradio, « OBD » sinon. */
    val source: String = "OBD",
) {
    /** Température de la batterie estimée : l'extérieur, sinon l'eau si le moteur est froid (elle suit l'air). */
    val batteryTempC: Float? get() = outsideC ?: coolantC?.takeIf { it < 30 }?.toFloat()
    val verdict: CrankVerdict get() = CrankWatch.judge(restV, minV, batteryTempC)
    /** Vrai si la chute au démarreur a été lue : sans elle, la tension au repos seule ne dit pas si la batterie tient. */
    val measured: Boolean get() = minV != null
    /** v0.42 : texte du verdict. « Bonne » n'est jamais dit sans la chute au démarreur. */
    val verdictLabel: String get() = if (!measured && verdict == CrankVerdict.GOOD) "Repos correct" else verdict.label
}

/**
 * Santé de la batterie au démarrage. Moteur coupé, la boucle OBD lit la tension (ATRV du vLinker) plusieurs fois
 * par seconde ; quand le régime passe au-dessus de 400 tr/min, on garde la tension au repos d'avant et la plus
 * basse des 10 dernières secondes (chute au démarreur).
 *
 * v0.11 : le démarreur fait souvent chuter l'alimentation de l'autoradio et couper le Bluetooth ; la connexion
 * revient moteur tournant. Les relevés et l'état « moteur coupé » sont donc gardés d'une connexion à l'autre
 * (3 min), et si la chute n'a pas été saisie, le démarrage est quand même noté avec la tension au repos et la
 * tension de charge.
 */
object CrankWatch {
    private const val MAX_RECORDS = 40
    private const val CRANK_WINDOW_MS = 10_000L
    private const val KEEP_MS = 60_000L
    /** Au-delà, la coupure entre deux connexions est trop longue pour relier « avant » et « après ». */
    private const val SESSION_GAP_MS = 3 * 60_000L
    /** Au démarreur d'un diesel la tension descend toujours sous 11 V : au-dessus, la chute n'a pas été vue. */
    private const val CRANK_SEEN_BELOW = 11.0f

    private var prefs: SharedPreferences? = null
    private val _history = MutableStateFlow<List<CrankRecord>>(emptyList())

    /** Démarrages mesurés, le plus récent d'abord. */
    val history: StateFlow<List<CrankRecord>> = _history.asStateFlow()

    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var wasRunning: Boolean? = null
    private var lastSeenMs = 0L

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("battery", Context.MODE_PRIVATE)
        prefs = p
        _history.value = decode(p.getString("starts", null))
    }

    /**
     * Tension mini acceptable au démarreur selon la température de la batterie (v0.38). Table classique des tests de
     * charge : 9,6 V à 21 °C et plus, puis environ 0,028 V de moins par degré, jusqu'à 8,5 V à -18 °C. Le froid fait
     * chuter la tension d'une batterie saine : sans cette correction, un matin d'hiver la jugeait « faible ».
     */
    fun crankLimit(tempC: Float?): Float =
        if (tempC == null) 9.6f else (9.6f - 0.028f * (21f - tempC).coerceAtLeast(0f)).coerceIn(8.5f, 9.6f)

    /** Seuils d'un test au démarreur (corrigés du froid) avec une marge « à surveiller » de 0,6 V. */
    fun judge(restV: Float, minV: Float?, tempC: Float? = null): CrankVerdict {
        val limit = crankLimit(tempC)
        return when {
            (minV != null && minV < limit) || restV < 12.0f -> CrankVerdict.WEAK
            (minV != null && minV < limit + 0.6f) || restV < 12.3f -> CrankVerdict.FAIR
            else -> CrankVerdict.GOOD
        }
    }

    /** Âge maxi d'un démarrage pour juger la batterie d'aujourd'hui. */
    const val VERDICT_MAX_AGE_MS = 21L * 24 * 3600 * 1000

    /**
     * v0.42 : démarrage à montrer pour juger la batterie. Le plus récent si sa chute au démarreur a été lue (ou s'il
     * est déjà mauvais au repos) ; sinon le dernier démarrage mesuré de moins de 21 jours. Le 16/11, un démarrage du
     * soir noté par le CAN sans la chute (repos 12,6 V → « Bonne ») masquait celui du matin, mesuré à 8,6 V au
     * démarreur (« Faible »).
     */
    fun forVerdict(list: List<CrankRecord>, nowMs: Long = System.currentTimeMillis()): CrankRecord? {
        val newest = list.maxByOrNull { it.atMs } ?: return null
        if (newest.measured || newest.verdict != CrankVerdict.GOOD) return newest
        return list.filter { it.measured && nowMs - it.atMs in 0..VERDICT_MAX_AGE_MS }.maxByOrNull { it.atMs } ?: newest
    }

    /** Nouvelle connexion OBD : l'état n'est oublié que si la coupure a été longue. */
    @Synchronized
    fun resetSession(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastSeenMs > SESSION_GAP_MS) {
            wasRunning = null
            samples.clear()
        }
    }

    /** Vrai si un démarrage maintenant serait mesuré (moteur coupé, tension lue il y a moins de 3 s). */
    @Synchronized
    fun ready(nowMs: Long = System.currentTimeMillis()): Boolean =
        wasRunning == false && samples.isNotEmpty() && nowMs - samples.last().first < 3_000L

    /**
     * À appeler à chaque tour de la boucle OBD. [volts] peut être nul (pas lu à ce tour), [rpm] nul si le
     * calculateur n'a pas répondu (fréquent pendant le démarreur). Renvoie le démarrage s'il vient d'être noté.
     */
    @Synchronized
    fun onSample(volts: Float?, rpm: Int?, coolantC: Int?, nowMs: Long = System.currentTimeMillis()): CrankRecord? {
        lastSeenMs = nowMs
        volts?.let { samples.addLast(nowMs to it) }
        while (samples.isNotEmpty() && nowMs - samples.first().first > KEEP_MS) samples.removeFirst()
        if (rpm == null) return null
        val running = rpm > 400
        val before = wasRunning
        wasRunning = running
        if (before != false || !running) return null
        if (samples.isEmpty()) return null // rien lu moteur coupé : rien à dire

        val rest = samples.maxOf { it.second }
        val low = samples.filter { nowMs - it.first <= CRANK_WINDOW_MS }.minOfOrNull { it.second }
        val minV = low?.takeIf { it < CRANK_SEEN_BELOW && it < rest - 0.5f }
        samples.clear()
        val record = CrankRecord(nowMs, rest, minV, coolantC, outsideC = outsideNow())
        store((listOf(record) + _history.value).take(MAX_RECORDS))
        return record
    }

    private fun outsideNow(): Float? = runCatching { Vehicle.state.value.outsideTempC }.getOrNull()

    /** Tension au-dessus de laquelle l'alternateur charge (moteur tournant). */
    const val CHARGING_V = 13.3f
    /** Sous cette tension, l'alternateur ne charge pas : moteur coupé (après [OFF_CONFIRM_MS]). */
    private const val NOT_CHARGING_V = 13.0f
    private const val OFF_CONFIRM_MS = 30_000L
    private var lowSinceMs: Long? = null
    /** Dernière tension au repos reçue du CAN (moteur coupé, hors chute au démarreur). */
    private var canRest: Pair<Long, Float>? = null

    /**
     * v0.38 : tension lue par le boîtier CAN de l'autoradio (dès le contact, sans attendre le boîtier OBD qui met
     * une dizaine de secondes à se connecter : depuis le 13/11 il arrivait moteur déjà tourné, et plus aucun
     * démarrage n'était noté). Démarrage = la tension passe au-dessus de [CHARGING_V] (l'alternateur charge) après
     * des relevés au repos. Si l'OBD l'a déjà noté (ou le note ensuite), il n'y a pas de doublon : l'état
     * « moteur tournant » est partagé. La chute n'est saisie que si le boîtier CAN envoie la tension assez souvent.
     */
    @Synchronized
    fun onCanVolts(volts: Float, coolantC: Int?, nowMs: Long = System.currentTimeMillis(), outsideC: Float? = outsideNow()): CrankRecord? {
        lastSeenMs = nowMs
        if (volts >= CHARGING_V) {
            lowSinceMs = null
            if (wasRunning == true) return null
            // Le service FYT ne prévient que quand la valeur change : la dernière tension au repos peut dater de
            // plusieurs minutes (contact mis, tension stable). Elle reste valable 30 min.
            val restSamples = samples.filter { nowMs - it.first <= KEEP_MS && it.second < NOT_CHARGING_V }
            val lastRest = canRest?.takeIf { nowMs - it.first <= 30 * 60_000L }
            val wasOff = wasRunning == false
            wasRunning = true
            samples.clear()
            canRest = null
            // Autoradio allumé moteur déjà tourné (pas de relevé moteur coupé) : rien à mesurer.
            if (!wasOff || (restSamples.isEmpty() && lastRest == null)) return null
            val rest = (restSamples.map { it.second } + listOfNotNull(lastRest?.second)).filter { it >= CRANK_SEEN_BELOW }
                .maxOrNull() ?: return null
            val low = restSamples.filter { nowMs - it.first <= CRANK_WINDOW_MS + 5_000L }.minOfOrNull { it.second }
            val minV = low?.takeIf { it < CRANK_SEEN_BELOW && it < rest - 0.5f }
            val record = CrankRecord(nowMs, rest, minV, coolantC, chargeV = volts, outsideC = outsideC, source = "CAN")
            store((listOf(record) + _history.value).take(MAX_RECORDS))
            return record
        }
        samples.addLast(nowMs to volts)
        while (samples.isNotEmpty() && nowMs - samples.first().first > KEEP_MS) samples.removeFirst()
        if (volts < NOT_CHARGING_V && volts >= CRANK_SEEN_BELOW) canRest = nowMs to volts
        if (volts < NOT_CHARGING_V) {
            when (wasRunning) {
                null -> wasRunning = false
                true -> {
                    val since = lowSinceMs ?: nowMs.also { lowSinceMs = it }
                    if (nowMs - since >= OFF_CONFIRM_MS) { wasRunning = false; lowSinceMs = null }
                }
                false -> Unit
            }
        }
        return null
    }

    /** Tension moteur tournant : les 3 premières minutes après un démarrage donnent la tension de charge. */
    @Synchronized
    fun onRunningVolts(volts: Float?, nowMs: Long = System.currentTimeMillis()) {
        if (volts == null) return
        val list = _history.value
        val last = list.firstOrNull() ?: return
        if (nowMs - last.atMs > 3 * 60_000L || nowMs < last.atMs) return
        if (last.chargeV != null && last.chargeV >= volts) return
        store(listOf(last.copy(chargeV = volts)) + list.drop(1))
    }

    private fun store(list: List<CrankRecord>) {
        _history.value = list
        prefs?.edit()?.putString("starts", encode(list))?.apply()
    }

    /** Écart de la tension mini entre les 5 derniers démarrages mesurés et les 5 d'avant (null s'il en manque). */
    fun trend(list: List<CrankRecord>): Float? {
        val measured = list.mapNotNull { it.minV }
        if (measured.size < 10) return null
        return measured.take(5).average().toFloat() - measured.drop(5).take(5).average().toFloat()
    }

    /** Charge de l'alternateur : 13,6 à 14,8 V attendu moteur tournant. */
    fun chargeText(v: Float): String = when {
        v < 13.3f -> "charge faible : alternateur à contrôler"
        v > 15.0f -> "charge trop forte : régulateur à contrôler"
        else -> "alternateur OK"
    }

    /** Exemple affiché en mode démo (jamais enregistré). */
    fun demoHistory(now: Long = System.currentTimeMillis()): List<CrankRecord> {
        val day = 86_400_000L
        val mins = listOf(10.6f, null, 10.6f, 10.4f, 10.5f, 10.3f, 10.4f, 10.2f, 10.3f, 10.1f)
        return mins.mapIndexed { i, m ->
            CrankRecord(now - i * day - 7 * 3_600_000L, 12.55f - i * 0.02f, m, if (i % 3 == 0) 6 else 45, 14.2f + (i % 3) * 0.1f)
        }
    }

    private fun encode(list: List<CrankRecord>) = JSONArray(list.map {
        JSONObject().put("t", it.atMs).put("rest", it.restV.toDouble()).put("min", it.minV?.toDouble() ?: -1.0)
            .put("eau", it.coolantC ?: -999).put("charge", it.chargeV?.toDouble() ?: -1.0)
            .put("ext", it.outsideC?.toDouble() ?: -999.0).put("source", it.source)
    }).toString()

    private fun decode(s: String?): List<CrankRecord> {
        if (s == null) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CrankRecord(
                    o.getLong("t"), o.getDouble("rest").toFloat(),
                    o.optDouble("min", -1.0).toFloat().takeIf { it > 0f },
                    o.optInt("eau", -999).takeIf { it != -999 },
                    o.optDouble("charge", -1.0).toFloat().takeIf { it > 0f },
                    o.optDouble("ext", -999.0).toFloat().takeIf { it > -900f },
                    o.optString("source", "OBD"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
