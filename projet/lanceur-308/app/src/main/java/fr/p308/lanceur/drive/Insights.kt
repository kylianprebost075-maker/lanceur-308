package fr.p308.lanceur.drive

import fr.p308.lanceur.maintenance.FuelEntry
import fr.p308.lanceur.vehicle.WarmRecord
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Consommation ESTIMÉE (la 308 ne donne pas le débit de gazole sur l'OBD normalisé) : un diesel consomme à peu près
 * en proportion de la puissance fournie (≈ 0,27 L par kWh) plus un ralenti (≈ 0,3 L/h). La puissance vient déjà
 * de l'OBD (charge × couple maxi). Le résultat est ensuite corrigé par les vrais pleins : entre deux pleins complets,
 * litres versés / litres estimés donne un facteur (médiane des 5 derniers, borné 0,6 à 1,6).
 */
object FuelModel {
    fun litersPerHour(powerCh: Int?): Double = 0.30 + 0.27 * (powerCh ?: 0).coerceAtLeast(0) * 0.7355

    @Volatile
    var factor: Double = 1.0
        private set

    /** Nombre d'intervalles entre pleins qui ont servi à corriger (0 = valeurs de départ). */
    @Volatile
    var samples: Int = 0
        private set

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    fun recompute(fills: List<FuelEntry>, trips: List<TripRecord>) {
        val fulls = fills.withIndex().filter { it.value.full }
        val factors = mutableListOf<Double>()
        for (k in 1 until fulls.size) {
            val a = fulls[k - 1]
            val b = fulls[k]
            val real = fills.subList(a.index + 1, b.index + 1).sumOf { it.liters.toDouble() }
            val kmOdo = (b.value.km - a.value.km).toDouble()
            val inRange = trips.filter { t -> day(t.startMs).let { !it.isBefore(a.value.date) && it.isBefore(b.value.date) } && t.fuelRawL > 0.05 }
            val tripKm = inRange.sumOf { it.distanceKm }
            val raw = inRange.sumOf { it.fuelRawL }
            // Il faut que l'OBD ait suivi au moins 60 % des km entre les deux pleins.
            if (kmOdo > 100 && real > 5 && raw > 0.5 && tripKm >= 0.6 * kmOdo) {
                factors += real / (raw / tripKm * kmOdo)
            }
        }
        val last = factors.takeLast(5).sorted()
        samples = last.size
        factor = if (last.isEmpty()) 1.0 else last[last.size / 2].coerceIn(0.6, 1.6)
    }
}

/**
 * Autonomie estimée : km avant l'allumage du témoin de réserve, litres restants, base du calcul.
 * v0.33 : [kmToEmpty] = km jusqu'au réservoir vide (réserve comprise), [start] = point de départ du calcul
 * (« plein du 03/11 » ou « niveau saisi le 11/11 : 25 L »), [sinceKm] = km parcourus depuis.
 */
data class RangeInfo(
    val km: Int, val liters: Double, val l100: Double, val basis: String,
    val kmToEmpty: Int = km, val start: String = "", val sinceKm: Int = 0,
)

/**
 * Autonomie ESTIMÉE (la 308 ne la donne pas sur la prise OBD) : on part du dernier point connu, soit le dernier plein
 * complet (réservoir de 60 L plein), soit le niveau saisi à la main s'il est plus récent (v0.33), on retire les km
 * parcourus depuis × la consommation, on ajoute les pleins partiels faits depuis. Consommation : celle
 * mesurée entre les pleins s'il y en a, sinon la moyenne estimée des derniers trajets, sinon 6 L/100.
 * Résultat donné jusqu'au témoin de réserve (≈ 6 L restants). Recalculé toutes les 30 s (le kilométrage avance en roulant).
 */
object FuelRange {
    const val TANK_L = fr.p308.lanceur.vehicle.CarProfile.TANK_L
    const val RESERVE_L = fr.p308.lanceur.vehicle.CarProfile.RESERVE_L

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<RangeInfo?>(null)
    val state: kotlinx.coroutines.flow.StateFlow<RangeInfo?> = _state
    private val DAY = java.time.format.DateTimeFormatter.ofPattern("dd/MM")

    /** Consommation à utiliser (L/100) et d'où elle vient : mesurée entre pleins, sinon estimée des trajets, sinon 6. */
    fun consumption(fills: List<FuelEntry>, trips: List<TripRecord>): Pair<Double, String> {
        val measured = fr.p308.lanceur.maintenance.Fuel.stats(fills).avgL100?.toDouble()
        val recent = trips.filter { it.l100 != null }.take(10)
        val estimated = if (recent.sumOf { it.distanceKm } >= 30) {
            recent.sumOf { it.fuelL ?: 0.0 } / recent.sumOf { it.distanceKm } * 100
        } else null
        val basis = when {
            measured != null -> "conso mesurée entre tes pleins"
            estimated != null -> "conso estimée des derniers trajets"
            else -> "conso moyenne par défaut, pas encore de mesure"
        }
        return (measured ?: estimated ?: 6.0).coerceIn(3.5, 12.0) to basis
    }

    fun compute(
        fills: List<FuelEntry>, trips: List<TripRecord>, odometerKm: Int?,
        level: fr.p308.lanceur.maintenance.TankLevel? = null,
    ): RangeInfo? {
        val lastFull = fills.withIndex().lastOrNull { it.value.full }
        val odo = odometerKm ?: return null
        // Le niveau saisi l'emporte s'il est au même kilométrage ou après le dernier plein complet (et pas plus ancien).
        val useLevel = level != null && (lastFull == null || level.km > lastFull.value.km ||
            (level.km == lastFull.value.km && !level.date.isBefore(lastFull.value.date)))
        if (!useLevel && lastFull == null) return null
        val startKm = if (useLevel) level!!.km else lastFull!!.value.km
        val startL = if (useLevel) level!!.liters.toDouble().coerceIn(0.0, TANK_L) else TANK_L
        val since = (odo - startKm).coerceAtLeast(0)
        if (since > 2_000) return null // point de départ trop ancien (ou kilométrage faux) : rien de fiable
        val (l100, basis) = consumption(fills, trips)
        // Pleins partiels versés après le point de départ.
        val partial = if (useLevel) fills.filter { !it.full && (it.km > level!!.km || (it.km == level.km && it.date.isAfter(level.date))) }
            .sumOf { it.liters.toDouble() }
        else fills.drop(lastFull!!.index + 1).sumOf { it.liters.toDouble() }
        val liters = (startL - since * l100 / 100 + partial).coerceIn(0.0, TANK_L)
        val km = ((liters - RESERVE_L).coerceAtLeast(0.0) / l100 * 100).toInt()
        val start = if (useLevel) "niveau saisi le ${level!!.date.format(DAY)} : %.0f L".format(java.util.Locale.FRANCE, level.liters)
        else "plein complet du ${lastFull!!.value.date.format(DAY)}"
        return RangeInfo(km, liters, l100, basis, kmToEmpty = (liters / l100 * 100).toInt(), start = start, sinceKm = since)
    }

    fun update(
        fills: List<FuelEntry>, trips: List<TripRecord>, odometerKm: Int?,
        level: fr.p308.lanceur.maintenance.TankLevel? = fr.p308.lanceur.maintenance.Fuel.level.value,
    ) {
        _state.value = compute(fills, trips, odometerKm, level)
    }
}

/** Plein repéré tout seul : litres ajoutés (estimés), moment, autonomie avant et après. */
data class DetectedFill(val liters: Double, val atMs: Long, val fromKm: Int, val toKm: Int)

/** Jauge déduite de l'ordinateur de bord : autonomie reçue, litres correspondants, dernier plein repéré. */
data class GaugeState(val canKm: Int? = null, val liters: Double? = null, val l100: Double = 6.0, val lastFill: DetectedFill? = null)

/**
 * Jauge automatique (v0.33). La 308 ne donne le niveau de gazole ni sur la prise OBD (12 PID seulement, pas le 2F ;
 * la jauge est au BSI, hors de portée du boîtier), ni tel quel sur le CAN de l'autoradio. Mais le boîtier CAN transmet
 * l'autonomie calculée par l'ordinateur de bord (module 7, code 103), qui suit la vraie jauge. On en déduit :
 * - les litres restants ≈ autonomie × consommation / 100 (estimation : la consommation est celle du Lanceur, pas
 *   celle, inconnue, que l'ordinateur de bord utilise) ;
 * - un plein : l'autonomie remonte d'un coup d'au moins [FILL_MIN_KM] km entre deux valeurs reçues (y compris d'un
 *   allumage de l'autoradio au suivant, la dernière valeur étant gardée) → litres ajoutés ≈ écart × consommation / 100.
 * En roulant, l'autonomie de l'ordinateur ne remonte que de quelques km (conduite plus douce) : jamais de 100 d'un coup.
 * Calculs purs vérifiés par TankGaugeTest.
 */
object TankGauge {
    const val FILL_MIN_KM = 100

    fun litersFromRange(rangeKm: Int, l100: Double): Double = (rangeKm * l100 / 100).coerceIn(0.0, FuelRange.TANK_L)

    fun detectFill(prevKm: Int?, newKm: Int, l100: Double, nowMs: Long): DetectedFill? =
        if (prevKm != null && newKm - prevKm >= FILL_MIN_KM)
            DetectedFill(((newKm - prevKm) * l100 / 100).coerceAtMost(FuelRange.TANK_L), nowMs, prevKm, newKm)
        else null

    private var prefs: android.content.SharedPreferences? = null
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(GaugeState())
    val state: kotlinx.coroutines.flow.StateFlow<GaugeState> = _state

    fun init(ctx: android.content.Context) {
        val p = ctx.getSharedPreferences("fuel", android.content.Context.MODE_PRIVATE)
        prefs = p
        val fill = if (p.contains("gaugeFillMs")) DetectedFill(p.getFloat("gaugeFillL", 0f).toDouble(), p.getLong("gaugeFillMs", 0),
            p.getInt("gaugeFillFrom", 0), p.getInt("gaugeFillTo", 0)) else null
        // L'autonomie gardée sert seulement à repérer un plein fait autoradio éteint : elle n'est pas affichée.
        _state.value = GaugeState(lastFill = fill)
    }

    /** Appelé à chaque autonomie reçue du CAN. Renvoie le plein repéré, s'il y en a un. */
    fun onCanRange(km: Int, nowMs: Long = System.currentTimeMillis()): DetectedFill? {
        val p = prefs
        val (l100, _) = FuelRange.consumption(fr.p308.lanceur.maintenance.Fuel.entries.value, TripLog.history.value)
        val prev = _state.value.canKm ?: p?.getInt("gaugeKm", -1)?.takeIf { it >= 0 }
        val fill = detectFill(prev, km, l100, nowMs)
        if (prev != km) p?.edit()?.putInt("gaugeKm", km)?.apply()
        if (fill != null) {
            p?.edit()?.putFloat("gaugeFillL", fill.liters.toFloat())?.putLong("gaugeFillMs", fill.atMs)
                ?.putInt("gaugeFillFrom", fill.fromKm)?.putInt("gaugeFillTo", fill.toKm)?.apply()
        }
        _state.value = GaugeState(km, litersFromRange(km, l100), l100, fill ?: _state.value.lastFill)
        return fill
    }
}

/** État du filtre à particules déduit des trajets (le FAP lui-même n'est pas lisible sur la prise OBD). */
data class FapStatus(
    /** 0 = rien à faire, 1 = conseil, 2 = à faire vite. */
    val level: Int,
    val tripsSince: Int,
    val shortSince: Int,
    val kmSince: Double,
    val daysSinceFast: Int?,
    val text: String,
)

/**
 * Un FAP se nettoie (régénération) quand les gaz sont très chauds longtemps : sur voie rapide. Les petits trajets
 * à froid le remplissent sans jamais le laisser se vider : première cause de FAP bouché sur les HDi.
 * « Bon » trajet : au moins 15 min à 70 km/h ou plus (ou, pour les trajets d'avant la v0.12, 25 km avec une pointe
 * à 90 km/h). On compte ce qui s'est passé depuis le dernier bon trajet.
 */
object FapAdvice {
    /** Dernier état calculé par le service (sert à l'alerte du panneau CarPlay). */
    @Volatile
    var current: FapStatus? = null

    private fun good(t: TripRecord) = if (t.fastS >= 0) t.fastS >= 900 else t.distanceKm >= 25 && t.maxKmh >= 90f

    fun evaluate(
        trips: List<TripRecord>,
        nowMs: Long = System.currentTimeMillis(),
        regenEndMs: Long? = fr.p308.lanceur.vehicle.FapRegen.lastCompleteEndMs(),
    ): FapStatus? {
        if (trips.isEmpty()) return null
        // Une régénération complète repérée au débit d'air vaut une voie rapide : le filtre vient d'être nettoyé.
        val since = trips.takeWhile { !good(it) && (regenEndMs == null || it.startMs > regenEndMs) }
        val lastGood = trips.getOrNull(since.size)
        val short = since.count { it.distanceKm < 10 || it.movingS < 900 }
        val km = since.sumOf { it.distanceKm }
        val days = lastGood?.let { ((nowMs - it.startMs) / 86_400_000L).toInt() }
        val oldestDays = since.lastOrNull()?.let { ((nowMs - it.startMs) / 86_400_000L).toInt() } ?: 0
        val level = when {
            short >= 20 || km >= 600 -> 2
            short >= 10 || km >= 300 || ((days ?: oldestDays) >= 14 && since.size >= 4) -> 1
            else -> 0
        }
        val text = when (level) {
            2 -> "À faire vite : $short petits trajets depuis la dernière voie rapide. Roule 20 à 30 min entre 90 et " +
                "110 km/h (vers 2 500 tr/min, 4e ou 5e) pour que le FAP se nettoie."
            1 -> "Fais 20 min de voie rapide cette semaine (90 à 110 km/h, vers 2 500 tr/min) : $short petit(s) trajet(s) " +
                "depuis la dernière."
            else -> if (days != null) "Dernière voie rapide il y a ${if (days == 0) "moins d'un jour" else "$days j"} : rien à faire."
            else "Rien à faire pour l'instant."
        }
        return FapStatus(level, since.size, short, km, days, text)
    }
}

data class MonthStats(
    val km: Double,
    val trips: Int,
    val drivingH: Double,
    val fuelL: Double?,
    val avgL100: Double?,
    val avgScore: Int?,
    val avgWarmMin: Float?,
    val fillSpend: Double,
    val fillLiters: Double,
)

/** Bilan d'un mois : trajets, gazole estimé, score, chauffes, pleins. */
object MonthReport {
    private fun ym(ms: Long) = YearMonth.from(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun compute(month: YearMonth, trips: List<TripRecord>, warm: List<WarmRecord>, fills: List<FuelEntry>): MonthStats {
        val t = trips.filter { ym(it.startMs) == month }
        val withFuel = t.filter { it.fuelL != null }
        val fuelKm = withFuel.sumOf { it.distanceKm }
        val fuel = withFuel.sumOf { it.fuelL ?: 0.0 }
        val w = warm.filter { ym(it.startMs) == month }
        val f = fills.filter { YearMonth.from(it.date) == month }
        return MonthStats(
            km = t.sumOf { it.distanceKm },
            trips = t.size,
            drivingH = t.sumOf { it.movingS } / 3600,
            fuelL = fuel.takeIf { withFuel.isNotEmpty() },
            avgL100 = if (fuelKm >= 5) fuel / fuelKm * 100 else null,
            avgScore = t.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.roundToInt(),
            avgWarmMin = w.takeIf { it.isNotEmpty() }?.map { it.minutes }?.average()?.toFloat(),
            fillSpend = f.sumOf { it.price.toDouble() },
            fillLiters = f.sumOf { it.liters.toDouble() },
        )
    }

    /** Prix du litre du dernier plein (pour chiffrer le gazole estimé). */
    fun lastPricePerL(fills: List<FuelEntry>): Double? =
        fills.maxByOrNull { it.date }?.pricePerL?.toDouble()?.takeIf { it > 0.5 }
}
