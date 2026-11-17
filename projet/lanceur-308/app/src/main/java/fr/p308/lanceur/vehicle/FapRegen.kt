package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Une régénération du FAP détectée : début, fin, kilométrage au début, interrompue (moteur coupé) ou non. */
data class RegenRecord(val startMs: Long, val endMs: Long, val km: Int?, val interrupted: Boolean) {
    val minutes: Int get() = ((endMs - startMs) / 60_000L).toInt()
}

data class FapRegenState(
    /** Régénération probable en cours. */
    val active: Boolean = false,
    val activeSinceMs: Long? = null,
    /** Apprentissage du débit d'air normal moteur chaud, 0 à 100 % (détection possible à partir de 100). */
    val learnPct: Int = 0,
    /** Débit d'air mesuré / débit normal au même régime et à la même charge (1,0 = normal). */
    val ratio: Float? = null,
    /** Les plus récentes d'abord. */
    val records: List<RegenRecord> = emptyList(),
)

/**
 * Régénérations du FAP, détectées SANS lire le FAP : le calculateur EDC16C34 ne le donne pas aux lectures autorisées
 * (séance du 08/11/2026 : services 21, 22 et 1A). Pendant une régénération, le calculateur ferme la vanne EGR (et
 * ajoute une post-injection) : il n'entre alors plus de gaz d'échappement dans l'admission, et le débitmètre (PID 10)
 * voit nettement plus d'air frais que d'habitude au même régime et à la même charge.
 *
 * Le Lanceur apprend donc, moteur chaud, le débit d'air normal par case régime × charge, puis signale une régénération
 * probable quand le débit reste au moins 25 % au-dessus de la normale pendant 2 minutes, dans les zones où l'EGR est
 * normalement ouverte (charge faible à moyenne, régime sous 3 000 tr/min). Fin quand le débit redevient normal.
 * Moins de 4 min : ignoré (bruit). Moteur coupé pendant une régénération : notée « interrompue ».
 * Tout est noté dans le journal OBD (débit mesuré et attendu) pour vérifier la détection.
 */
object FapRegen {
    private const val RPM_MIN = 750
    private const val RPM_STEP = 250
    private const val RPM_CELLS = 10 // 750..3250
    private const val LOAD_CELLS = 7 // 0..69 %
    private const val CELLS_NEEDED = 12 // cases bien apprises pour pouvoir détecter
    private const val SAMPLES_PER_CELL = 40
    private const val HIGH = 1.25f
    private const val WINDOW = 120
    private const val MIN_REGEN_MS = 4 * 60_000L

    /**
     * v0.41 : deuxième indice, la post-injection. Elle ne se lit pas (12 PID seulement, service 21 sans mesure en
     * direct), mais c'est du gazole brûlé sans effort moteur : la conso instantanée de l'ordinateur de bord (CAN
     * 7/102, v0.39) × la vitesse donne des litres par heure, comparés à la normale apprise par case régime × charge
     * (toutes les charges, 0 à 99 %). Pas encore vérifié sur une vraie régénération : ce signal seul ne déclenche
     * rien, il confirme le débit d'air (seuils plus bas quand les deux sont d'accord) et il est noté au journal.
     */
    private const val FUEL_LOAD_CELLS = 10
    private const val FUEL_HIGH = 1.25f
    private const val FUEL_MIN_KMH = 25f
    private val fuelBase = FloatArray(RPM_CELLS * FUEL_LOAD_CELLS)
    private val fuelCount = IntArray(RPM_CELLS * FUEL_LOAD_CELLS)
    private val fuelWindow = ArrayDeque<Int>()
    private var lastInst: Float? = null
    private var instChanges = 0
    private var fuelAloneLogMs = 0L

    /** Litres par heure d'après la conso instantanée (L/100) et la vitesse ; null à basse vitesse ou sans valeur. */
    fun fuelLph(instL100: Float?, kmh: Float): Float? =
        if (instL100 == null || kmh < FUEL_MIN_KMH) null else instL100 * kmh / 100f

    /**
     * v0.41 : eau minimale pour apprendre et détecter. 75 °C normalement ; avec un thermostat resté ouvert l'eau
     * plafonne vers 71-77 °C et la moitié des mesures étaient ignorées : 4 °C sous la cible de chauffe, 60 au moins.
     */
    fun warmMinC(target: Int = Warmup.effectiveTarget()): Int = (target - 4).coerceIn(60, 75)

    private fun fuelCell(rpm: Int, load: Int): Int? {
        val r = (rpm - RPM_MIN) / RPM_STEP
        val l = load / 10
        if (rpm < RPM_MIN || r !in 0 until RPM_CELLS || l !in 0 until FUEL_LOAD_CELLS) return null
        return r * FUEL_LOAD_CELLS + l
    }

    private val _state = MutableStateFlow(FapRegenState())
    val state: StateFlow<FapRegenState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private val base = FloatArray(RPM_CELLS * LOAD_CELLS)
    private val count = IntArray(RPM_CELLS * LOAD_CELLS)
    /** 1 = débit haut, 0 = normal, sur les 120 dernières secondes utiles. */
    private val window = ArrayDeque<Int>()
    private var startMs = 0L
    private var startKm: Int? = null
    private var lastMs = 0L
    private var calmSince = 0L
    private var lastSaveMs = 0L
    private var lastLogMs = 0L
    private var wasRunning = false
    private var wasDemo = false

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("fap", Context.MODE_PRIVATE)
        prefs = p
        p.getString("base", null)?.split(';')?.forEachIndexed { i, s ->
            if (i >= base.size) return@forEachIndexed
            val (b, c) = s.split(':').let { (it.getOrNull(0)?.toFloatOrNull() ?: 0f) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            base[i] = b
            count[i] = c
        }
        p.getString("fuelBase", null)?.split(';')?.forEachIndexed { i, s ->
            if (i >= fuelBase.size) return@forEachIndexed
            val f = s.split(':')
            fuelBase[i] = f.getOrNull(0)?.toFloatOrNull() ?: 0f
            fuelCount[i] = f.getOrNull(1)?.toIntOrNull() ?: 0
        }
        val records = p.getString("records", null)?.split(';')?.mapNotNull(::decode).orEmpty()
        _state.value = FapRegenState(learnPct = learnPct(), records = records)
    }

    private fun decode(s: String): RegenRecord? {
        val f = s.split(',')
        return RegenRecord(
            f.getOrNull(0)?.toLongOrNull() ?: return null,
            f.getOrNull(1)?.toLongOrNull() ?: return null,
            f.getOrNull(2)?.toIntOrNull(),
            f.getOrNull(3) == "1",
        )
    }

    private fun encode(r: RegenRecord) = "${r.startMs},${r.endMs},${r.km ?: ""},${if (r.interrupted) 1 else 0}"

    private fun learnPct(): Int = (count.count { it >= SAMPLES_PER_CELL } * 100 / CELLS_NEEDED).coerceAtMost(100)

    private fun cell(rpm: Int, load: Int): Int? {
        val r = (rpm - RPM_MIN) / RPM_STEP
        val l = load / 10
        if (rpm < RPM_MIN || r !in 0 until RPM_CELLS || l !in 0 until LOAD_CELLS) return null
        return r * LOAD_CELLS + l
    }

    /** Appelé chaque seconde par le service. `odometerKm` sert à noter le kilométrage de la régénération. */
    @Synchronized
    fun tick(e: EngineState, kmh: Float, odometerKm: Int?, nowMs: Long = System.currentTimeMillis()) {
        if (Vehicle.demo) {
            wasDemo = true
            demo(nowMs)
            return
        }
        if (wasDemo) {
            // Sortie de la démo : on remet les vraies données enregistrées.
            wasDemo = false
            prefs?.let { p -> _state.value = FapRegenState(learnPct = learnPct(),
                records = p.getString("records", null)?.split(';')?.mapNotNull(::decode).orEmpty()) }
        }
        val rpm = e.rpm ?: 0
        val running = rpm > 400 && e.connected
        if (!running) {
            // Moteur coupé pendant une régénération : interrompue. Coupure Bluetooth : on attend 5 min avant de conclure
            // (le boîtier se reconnecte souvent tout seul, moteur toujours tournant).
            if (startMs > 0 && (e.connected && e.rpm != null || nowMs - lastMs > 5 * 60_000L)) {
                finish(lastMs.coerceAtLeast(startMs), interrupted = true)
            }
            wasRunning = false
            window.clear()
            fuelWindow.clear()
            return
        }
        wasRunning = true
        if (nowMs - lastMs < 800) return
        lastMs = nowMs
        val maf = e.mafGs ?: return
        val load = e.loadPct ?: return
        val coolant = e.coolantC ?: return
        // Moteur froid : l'EGR est fermée de toute façon. Décélération (charge ≈ 0) : injection coupée.
        if (coolant < warmMinC() || load < 8) return
        // Débit d'air : seulement là où l'EGR est normalement ouverte (charge sous 70 %, régime sous 3 250).
        val c = cell(rpm, load)
        val expected = c?.let { base[it].takeIf { _ -> count[it] >= SAMPLES_PER_CELL } }
        val ratio = expected?.let { maf / it }
        if (ratio != null) {
            window.addLast(if (ratio >= HIGH) 1 else 0)
            while (window.size > WINDOW) window.removeFirst()
        }
        val highShare = if (window.size >= 40) window.sum().toFloat() / window.size else 0f
        // v0.41 : gazole en trop (post-injection), à toutes les charges.
        val inst = Vehicle.state.value.instantL100
        if (inst != lastInst) { lastInst = inst; instChanges++ }
        val lph = fuelLph(inst, kmh)
        val fc = fuelCell(rpm, load)
        val fuelExpected = fc?.let { fuelBase[it].takeIf { _ -> fuelCount[it] >= SAMPLES_PER_CELL } }
        val fuelRatio = if (lph != null && fuelExpected != null && fuelExpected > 0.2f) lph / fuelExpected else null
        if (fuelRatio != null) {
            fuelWindow.addLast(if (fuelRatio >= FUEL_HIGH) 1 else 0)
            while (fuelWindow.size > WINDOW) fuelWindow.removeFirst()
        }
        val fuelShare = if (fuelWindow.size >= 40) fuelWindow.sum().toFloat() / fuelWindow.size else 0f
        // Apprentissage lent, jamais pendant une régénération (ni quand elle semble commencer).
        val calm = startMs == 0L && highShare < 0.3f && fuelShare < 0.3f
        if (c != null && calm && (ratio == null || ratio < HIGH)) {
            val n = count[c]
            base[c] = if (n == 0) maf else base[c] + (maf - base[c]) * (if (n < SAMPLES_PER_CELL) 1f / (n + 1) else 0.02f)
            count[c] = (n + 1).coerceAtMost(10_000)
        }
        if (fc != null && lph != null && calm && (fuelRatio == null || fuelRatio < FUEL_HIGH)) {
            val n = fuelCount[fc]
            fuelBase[fc] = if (n == 0) lph else fuelBase[fc] + (lph - fuelBase[fc]) * (if (n < SAMPLES_PER_CELL) 1f / (n + 1) else 0.02f)
            fuelCount[fc] = (n + 1).coerceAtMost(10_000)
        }
        // Départ : débit d'air seul (comme avant), ou les deux indices d'accord avec des seuils plus bas.
        val airAlone = window.size >= 60 && highShare >= 0.7f
        val both = window.size >= 40 && fuelWindow.size >= 40 && highShare >= 0.4f && fuelShare >= 0.6f
        if (startMs == 0L && learnPct() >= 100 && (airAlone || both)) {
            startMs = nowMs - 60_000L
            startKm = odometerKm
            calmSince = 0L
            ObdLog.log("FAP", "régénération probable (${if (airAlone) "débit d'air" else "débit d'air + gazole en trop"}) : " +
                "air ${fmt(maf)} g/s au lieu de ${fmt(expected)}, gazole ${fmt(lph)} L/h au lieu de ${fmt(fuelExpected)} " +
                "($rpm tr/min, charge $load %, eau $coolant °C)")
        } else if (startMs > 0L) {
            if (highShare < 0.3f && fuelShare < 0.5f) {
                if (calmSince == 0L) calmSince = nowMs
                if (nowMs - calmSince >= 30_000L) finish(calmSince - 60_000L, interrupted = false)
            } else calmSince = 0L
        } else if (fuelWindow.size >= 60 && fuelShare >= 0.7f && nowMs - fuelAloneLogMs >= 10 * 60_000L) {
            // Gazole en trop sans débit d'air haut : noté pour caler le signal, pas compté comme régénération.
            fuelAloneLogMs = nowMs
            ObdLog.log("FAP", "gazole en trop sans débit d'air haut (non compté) : ${fmt(lph)} L/h au lieu de " +
                "${fmt(fuelExpected)} ($rpm tr/min, charge $load %, air ${fmt(maf)} / ${fmt(expected)} g/s)")
        }
        if (nowMs - lastLogMs >= 10 * 60_000L) {
            lastLogMs = nowMs
            ObdLog.log("FAP", "débit d'air ${fmt(maf)} g/s, normal ${fmt(expected)} g/s ($rpm tr/min, charge $load %), " +
                "apprentissage ${learnPct()} % ; gazole ${fmt(lph)} L/h, normal ${fmt(fuelExpected)} L/h, " +
                "${fuelCount.count { it >= SAMPLES_PER_CELL }} cases apprises, conso instantanée changée $instChanges fois en 10 min ; " +
                "parts hautes air ${(highShare * 100).roundToInt()} %, gazole ${(fuelShare * 100).roundToInt()} %")
            instChanges = 0
        }
        if (nowMs - lastSaveMs >= 5 * 60_000L) save(nowMs)
        _state.value = _state.value.copy(
            active = startMs > 0L, activeSinceMs = startMs.takeIf { it > 0 }, learnPct = learnPct(), ratio = ratio,
        )
    }

    private fun finish(endMs: Long, interrupted: Boolean) {
        val s = startMs
        startMs = 0L
        calmSince = 0L
        window.clear()
        fuelWindow.clear()
        val dur = endMs - s
        if (dur < MIN_REGEN_MS && !interrupted) {
            ObdLog.log("FAP", "débit haut pendant ${dur / 60_000L} min seulement : ignoré")
            _state.value = _state.value.copy(active = false, activeSinceMs = null)
            return
        }
        val r = RegenRecord(s, endMs, startKm, interrupted)
        ObdLog.log("FAP", if (interrupted) "régénération interrompue : moteur coupé après ${r.minutes} min"
            else "régénération terminée en ${r.minutes} min")
        val list = (listOf(r) + _state.value.records).take(30)
        _state.value = _state.value.copy(active = false, activeSinceMs = null, records = list)
        save(System.currentTimeMillis())
    }

    private fun save(nowMs: Long) {
        lastSaveMs = nowMs
        prefs?.edit()
            ?.putString("base", base.indices.joinToString(";") { "${"%.2f".format(java.util.Locale.US, base[it])}:${count[it]}" })
            ?.putString("fuelBase", fuelBase.indices.joinToString(";") { "${"%.2f".format(java.util.Locale.US, fuelBase[it])}:${fuelCount[it]}" })
            ?.putString("records", _state.value.records.joinToString(";", transform = ::encode))
            ?.apply()
    }

    private fun fmt(v: Float?) = v?.let { "%.1f".format(java.util.Locale.FRANCE, it) } ?: "?"

    /** Démo : une régénération en cours et deux passées, pour voir l'affichage (rien n'est enregistré). */
    private fun demo(nowMs: Long) {
        if (_state.value.learnPct == 100 && _state.value.active) return
        val day = 86_400_000L
        _state.value = FapRegenState(
            active = true, activeSinceMs = nowMs - 6 * 60_000L, learnPct = 100, ratio = 1.42f,
            records = listOf(
                RegenRecord(nowMs - 9 * day, nowMs - 9 * day + 14 * 60_000L, 139_920, false),
                RegenRecord(nowMs - 21 * day, nowMs - 21 * day + 6 * 60_000L, 139_310, true),
            ),
        )
    }

    /**
     * v0.44 : régénération terminée selon le calculateur (21 CA : km depuis la dernière repassés près de zéro). C'est
     * la mesure sûre : notée comme régénération complète, sauf si le débit d'air vient déjà d'en noter une (2 h).
     */
    @Synchronized
    fun onEcuRegenDone(nowMs: Long, odometerKm: Int?) {
        if (Vehicle.demo) return
        val list = _state.value.records
        if (list.any { !it.interrupted && nowMs - it.endMs in 0..2 * 3_600_000L }) return
        val r = RegenRecord(startMs = nowMs - 10 * 60_000L, endMs = nowMs, km = odometerKm, interrupted = false)
        _state.value = _state.value.copy(records = (listOf(r) + list).take(30))
        save(nowMs)
    }

    /** Fin de la dernière régénération complète (sert au conseil voie rapide). */
    fun lastCompleteEndMs(): Long? = _state.value.records.firstOrNull { !it.interrupted }?.endMs

    /** Moyenne des km entre deux régénérations complètes (null s'il en manque). */
    fun avgKmBetween(): Int? {
        val kms = _state.value.records.filter { !it.interrupted }.mapNotNull { it.km }
        if (kms.size < 2) return null
        return ((kms.first() - kms.last()).toFloat() / (kms.size - 1)).roundToInt().takeIf { it > 0 }
    }
}
