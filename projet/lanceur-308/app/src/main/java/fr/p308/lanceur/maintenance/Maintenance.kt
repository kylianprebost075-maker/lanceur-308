package fr.p308.lanceur.maintenance

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class MaintenanceItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val everyKm: Int = 0,
    val everyMonths: Int = 0,
    val lastKm: Int? = null,
    val lastDate: LocalDate? = null,
    val note: String = "",
)

enum class DueLevel { NOT_SET, OK, SOON, LATE }

data class Due(val kmLeft: Int?, val daysLeft: Long?, val progress: Float, val level: DueLevel)

const val SOON_KM = 1_000
const val SOON_DAYS = 30L

fun MaintenanceItem.due(currentKm: Int?, today: LocalDate = LocalDate.now()): Due {
    // Vidange : les km depuis la dernière comptent davantage si l'usage est sévère (petits trajets, voir OilWear).
    val kmLeft = if (everyKm > 0 && lastKm != null && currentKm != null) {
        val driven = currentKm - lastKm
        everyKm - if (OilWear.isOil(this)) OilWear.effectiveKm(driven) else driven
    } else null
    val dueDate = if (everyMonths > 0 && lastDate != null) lastDate.plusMonths(everyMonths.toLong()) else null
    val daysLeft = dueDate?.let { ChronoUnit.DAYS.between(today, it) }
    if (kmLeft == null && daysLeft == null) return Due(null, null, 0f, DueLevel.NOT_SET)

    val pKm = kmLeft?.let { 1f - it.toFloat() / everyKm } ?: 0f
    val pDays = if (daysLeft != null && dueDate != null && lastDate != null) {
        1f - daysLeft.toFloat() / ChronoUnit.DAYS.between(lastDate, dueDate).coerceAtLeast(1)
    } else 0f
    val level = when {
        (kmLeft ?: 1) < 0 || (daysLeft ?: 1L) < 0 -> DueLevel.LATE
        (kmLeft ?: Int.MAX_VALUE) <= SOON_KM || (daysLeft ?: Long.MAX_VALUE) <= SOON_DAYS -> DueLevel.SOON
        else -> DueLevel.OK
    }
    return Due(kmLeft, daysLeft, maxOf(pKm, pDays).coerceIn(0f, 1f), level)
}

fun fmtKm(km: Int): String = String.format(Locale.FRANCE, "%,d", km).replace(' ', ' ')

private fun fmtDays(d: Long) = if (d < 60) "$d j" else "${d / 30} mois"

fun Due.label(): String {
    if (level == DueLevel.NOT_SET) return "À renseigner"
    val parts = buildList {
        kmLeft?.let { add("${fmtKm(kotlin.math.abs(it))} km") }
        daysLeft?.let { add(fmtDays(kotlin.math.abs(it))) }
    }.joinToString(" · ")
    return if (level == DueLevel.LATE) "Dépassé : $parts" else "Dans $parts"
}

fun MaintenanceItem.intervalLabel(): String = listOfNotNull(
    everyKm.takeIf { it > 0 }?.let { "${fmtKm(it)} km" },
    everyMonths.takeIf { it > 0 }?.let { if (it % 12 == 0) "${it / 12} an${if (it > 12) "s" else ""}" else "$it mois" },
).joinToString(" · ").let { if (it.isEmpty()) "sans intervalle" else "tous les $it" }

/** Valeurs de départ pour une 308 T7 1.6 HDi 110 (DV6TED4) : à ajuster selon le carnet d'entretien. */
object Defaults308 {
    fun items() = listOf(
        MaintenanceItem(name = "Vidange + filtre à huile", everyKm = 15_000, everyMonths = 12,
            note = "Huile 5W-30 norme PSA B71 2290. 15 000 km conseillés sur le DV6 (PSA prévoit davantage) pour préserver le turbo."),
        MaintenanceItem(name = "Filtre à air", everyKm = 60_000, everyMonths = 48),
        MaintenanceItem(name = "Filtre d'habitacle", everyKm = 20_000, everyMonths = 12),
        MaintenanceItem(name = "Filtre à gasoil", everyKm = 60_000),
        MaintenanceItem(name = "Liquide de frein", everyMonths = 24),
        MaintenanceItem(name = "Courroie de distribution + pompe à eau", everyKm = 150_000, everyMonths = 120,
            note = "À vérifier dans le carnet : la préconisation PSA du DV6 a changé selon les années."),
        MaintenanceItem(name = "Additif FAP (Eolys)", everyKm = 120_000,
            note = "Si la voiture a un FAP. À vérifier selon le carnet et le témoin d'additif."),
        controleTechnique(),
        assurance(),
    )

    const val CT_NOTE = "Mets la date du dernier contrôle : rappel 30 jours puis 7 jours avant. En cas de contre-visite, " +
        "2 mois pour faire réparer."

    fun controleTechnique() = MaintenanceItem(name = "Contrôle technique", everyMonths = 24, note = CT_NOTE)

    fun assurance() = MaintenanceItem(name = "Assurance (échéance)", everyMonths = 12,
        note = "Mets la date de la dernière échéance : rappel 30 jours avant, le temps de comparer les offres.")

    /** Version des éléments par défaut : les nouveaux sont ajoutés une seule fois aux listes existantes. */
    const val VERSION = 3
}

object Maintenance {
    private lateinit var prefs: SharedPreferences
    private val _items = MutableStateFlow<List<MaintenanceItem>>(emptyList())
    val items: StateFlow<List<MaintenanceItem>> = _items.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        val stored = prefs.getString("items", null)
        _items.value = if (stored == null) Defaults308.items().also(::save) else decode(stored)
        if (prefs.getInt("defaultsVersion", 1) < 2) {
            // v0.10 : rappel d'assurance ajouté (sauf si l'utilisateur en a déjà créé un).
            if (_items.value.none { it.name.contains("assurance", ignoreCase = true) }) save(_items.value + Defaults308.assurance())
        }
        if (prefs.getInt("defaultsVersion", 1) < 3) {
            // v0.12 : contrôle technique ajouté s'il manque, explication ajoutée s'il n'en a pas.
            val ct = _items.value.firstOrNull { isCt(it) }
            if (ct == null) save(_items.value + Defaults308.controleTechnique())
            else if (ct.note.isBlank()) save(_items.value.map { if (it.id == ct.id) it.copy(note = Defaults308.CT_NOTE) else it })
        }
        prefs.edit().putInt("defaultsVersion", Defaults308.VERSION).apply()
    }

    fun isCt(item: MaintenanceItem) = item.name.contains("contrôle technique", ignoreCase = true) ||
        item.name.contains("controle technique", ignoreCase = true)

    fun upsert(item: MaintenanceItem) {
        val list = _items.value
        save(if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item)
    }

    fun delete(id: String) = save(_items.value.filterNot { it.id == id })

    private fun save(list: List<MaintenanceItem>) {
        _items.value = list
        prefs.edit().putString("items", encode(list)).apply()
    }

    private fun encode(list: List<MaintenanceItem>) = JSONArray(list.map {
        JSONObject()
            .put("id", it.id).put("name", it.name)
            .put("everyKm", it.everyKm).put("everyMonths", it.everyMonths)
            .put("lastKm", it.lastKm ?: -1).put("lastDate", it.lastDate?.toString() ?: "")
            .put("note", it.note)
    }).toString()

    private fun decode(s: String): List<MaintenanceItem> {
        val arr = JSONArray(s)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            MaintenanceItem(
                id = o.getString("id"),
                name = o.getString("name"),
                everyKm = o.optInt("everyKm"),
                everyMonths = o.optInt("everyMonths"),
                lastKm = o.optInt("lastKm", -1).takeIf { it >= 0 },
                lastDate = o.optString("lastDate").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                note = o.optString("note"),
            )
        }
    }
}
