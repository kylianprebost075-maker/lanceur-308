package fr.p308.lanceur.maintenance

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class FuelEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val km: Int,
    val liters: Float,
    val price: Float,
    val full: Boolean = true,
) {
    val pricePerL: Float get() = if (liters > 0) price / liters else 0f
}

data class FuelStats(
    val avgL100: Float? = null,
    val lastL100: Float? = null,
    val costPerKm: Float? = null,
    val monthSpend: Float = 0f,
)

/**
 * v0.33 : niveau du réservoir saisi à la main (« il me reste environ 25 L » à tel kilométrage). Sert de point de
 * départ au calcul de l'autonomie quand il est plus récent que le dernier plein complet.
 */
data class TankLevel(val liters: Float, val km: Int, val date: LocalDate)

/** Carnet de pleins : consommation réelle mesurée entre deux pleins complets. */
object Fuel {
    private lateinit var prefs: SharedPreferences
    private val _entries = MutableStateFlow<List<FuelEntry>>(emptyList())
    val entries: StateFlow<List<FuelEntry>> = _entries.asStateFlow()
    private val _level = MutableStateFlow<TankLevel?>(null)
    val level: StateFlow<TankLevel?> = _level.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("fuel", Context.MODE_PRIVATE)
        _entries.value = decode(prefs.getString("entries", "[]")!!)
        _level.value = if (prefs.contains("levelKm")) runCatching {
            TankLevel(prefs.getFloat("levelL", 0f), prefs.getInt("levelKm", 0), LocalDate.parse(prefs.getString("levelDate", "")))
        }.getOrNull() else null
    }

    fun setLevel(liters: Float, km: Int, date: LocalDate = LocalDate.now()) {
        _level.value = TankLevel(liters, km, date)
        prefs.edit().putFloat("levelL", liters).putInt("levelKm", km).putString("levelDate", date.toString()).apply()
    }

    fun clearLevel() {
        _level.value = null
        prefs.edit().remove("levelL").remove("levelKm").remove("levelDate").apply()
    }

    fun add(e: FuelEntry) = save(_entries.value.filterNot { it.id == e.id } + e)
    fun delete(id: String) = save(_entries.value.filterNot { it.id == id })

    private fun save(list: List<FuelEntry>) {
        val sorted = list.sortedWith(compareBy({ it.km }, { it.date }))
        _entries.value = sorted
        prefs.edit().putString("entries", JSONArray(sorted.map {
            JSONObject().put("id", it.id).put("date", it.date.toString()).put("km", it.km)
                .put("liters", it.liters.toDouble()).put("price", it.price.toDouble()).put("full", it.full)
        }).toString()).apply()
    }

    private fun decode(s: String): List<FuelEntry> {
        val a = JSONArray(s)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            FuelEntry(o.getString("id"), LocalDate.parse(o.getString("date")), o.getInt("km"),
                o.getDouble("liters").toFloat(), o.getDouble("price").toFloat(), o.optBoolean("full", true))
        }
    }

    fun stats(list: List<FuelEntry> = _entries.value, today: LocalDate = LocalDate.now()): FuelStats {
        val month = list.filter { it.date.year == today.year && it.date.month == today.month }.sumOf { it.price.toDouble() }.toFloat()
        val fulls = list.withIndex().filter { it.value.full }
        if (fulls.size < 2) return FuelStats(monthSpend = month)
        // Litres versés après le 1er plein complet (ils ont servi à parcourir les km suivants).
        fun litersBetween(from: Int, to: Int) = list.subList(from + 1, to + 1).sumOf { it.liters.toDouble() }
        fun priceBetween(from: Int, to: Int) = list.subList(from + 1, to + 1).sumOf { it.price.toDouble() }
        val first = fulls.first()
        val last = fulls.last()
        val prevLast = fulls[fulls.size - 2]
        val km = (last.value.km - first.value.km).toDouble()
        val kmLast = (last.value.km - prevLast.value.km).toDouble()
        return FuelStats(
            avgL100 = if (km > 0) (litersBetween(first.index, last.index) / km * 100).toFloat() else null,
            lastL100 = if (kmLast > 0) (litersBetween(prevLast.index, last.index) / kmLast * 100).toFloat() else null,
            costPerKm = if (km > 0) (priceBetween(first.index, last.index) / km).toFloat() else null,
            monthSpend = month,
        )
    }
}
