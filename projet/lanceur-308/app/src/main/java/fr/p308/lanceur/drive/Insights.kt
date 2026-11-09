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

/** Autonomie estimée : km avant l'allumage du témoin de réserve, litres restants, base du calcul. */
data class RangeInfo(val km: Int, val liters: Double, val l100: Double, val basis: String)

/**
 * Autonomie ESTIMÉE (la 308 ne la donne pas sur la prise OBD) : réservoir de 60 L plein au dernier plein complet,
 * moins les km parcourus depuis × la consommation, plus les pleins partiels faits depuis. Consommation : celle
 * mesurée entre les pleins s'il y en a, sinon la moyenne estimée des derniers trajets, sinon 6 L/100.
 * Résultat donné jusqu'au témoin de réserve (≈ 6 L restants).
 */
object FuelRange {
    const val TANK_L = 60.0
    const val RESERVE_L = 6.0

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<RangeInfo?>(null)
    val state: kotlinx.coroutines.flow.StateFlow<RangeInfo?> = _state

    fun compute(fills: List<FuelEntry>, trips: List<TripRecord>, odometerKm: Int?): RangeInfo? {
        val lastFull = fills.withIndex().lastOrNull { it.value.full } ?: return null
        val odo = odometerKm ?: return null
        val since = (odo - lastFull.value.km).coerceAtLeast(0)
        if (since > 2_000) return null // plein trop ancien (ou kilométrage faux) : rien de fiable
        val measured = fr.p308.lanceur.maintenance.Fuel.stats(fills).avgL100?.toDouble()
        val recent = trips.filter { it.l100 != null }.take(10)
        val estimated = if (recent.sumOf { it.distanceKm } >= 30) {
            recent.sumOf { it.fuelL ?: 0.0 } / recent.sumOf { it.distanceKm } * 100
        } else null
        val l100 = (measured ?: estimated ?: 6.0).coerceIn(3.5, 12.0)
        val basis = when {
            measured != null -> "conso mesurée entre tes pleins"
            estimated != null -> "conso estimée des derniers trajets"
            else -> "conso moyenne par défaut, pas encore de mesure"
        }
        val partial = fills.drop(lastFull.index + 1).sumOf { it.liters.toDouble() }
        val liters = (TANK_L - since * l100 / 100 + partial).coerceIn(0.0, TANK_L)
        val km = ((liters - RESERVE_L).coerceAtLeast(0.0) / l100 * 100).toInt()
        return RangeInfo(km, liters, l100, basis)
    }

    fun update(fills: List<FuelEntry>, trips: List<TripRecord>, odometerKm: Int?) {
        _state.value = compute(fills, trips, odometerKm)
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
