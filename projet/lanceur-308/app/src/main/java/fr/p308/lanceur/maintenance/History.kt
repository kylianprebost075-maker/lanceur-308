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

/**
 * Une intervention passée (facture, contrôle technique, entretien fait soi-même) : date (peut manquer si
 * illisible), kilométrage, ce qui a été fait, où, et combien. Sert au suivi, pas aux rappels (ceux-ci sont dans
 * les éléments d'entretien, mis à jour en même temps lors d'un import).
 */
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate?,
    /** Précision de la date quand elle est déduite (« ≈ juin 2023 ») ; vide si la date est sûre. */
    val dateNote: String = "",
    val km: Int?,
    val title: String,
    val details: String = "",
    val place: String = "",
    val priceEur: Float? = null,
)

/** Historique des interventions, le plus récent d'abord (trié par kilométrage puis date). */
object History {
    private var prefs: SharedPreferences? = null
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
        prefs = p
        _entries.value = decode(p.getString("entries", "[]")!!)
    }

    /** Ajoute ou remplace (même identifiant). Renvoie le nombre d'entrées nouvelles. */
    fun merge(list: List<HistoryEntry>): Int {
        val known = _entries.value.map { it.id }.toSet()
        save((_entries.value.associateBy { it.id } + list.associateBy { it.id }).values.toList())
        return list.count { it.id !in known }
    }

    fun delete(id: String) = save(_entries.value.filterNot { it.id == id })

    private fun save(list: List<HistoryEntry>) {
        val sorted = list.sortedWith(compareByDescending<HistoryEntry> { it.km ?: -1 }.thenByDescending { it.date })
        _entries.value = sorted
        prefs?.edit()?.putString("entries", JSONArray(sorted.map(::encode)).toString())?.apply()
    }

    fun encode(e: HistoryEntry): JSONObject = JSONObject()
        .put("id", e.id).put("date", e.date?.toString() ?: "").put("dateNote", e.dateNote)
        .put("km", e.km ?: -1).put("title", e.title).put("details", e.details).put("place", e.place)
        .put("price", e.priceEur?.toDouble() ?: -1.0)

    fun decodeOne(o: JSONObject) = HistoryEntry(
        id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
        date = o.optString("date").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        dateNote = o.optString("dateNote"),
        km = o.optInt("km", -1).takeIf { it >= 0 },
        title = o.optString("title"),
        details = o.optString("details"),
        place = o.optString("place"),
        priceEur = o.optDouble("price", -1.0).toFloat().takeIf { it >= 0f },
    )

    private fun decode(s: String): List<HistoryEntry> = runCatching {
        val a = JSONArray(s)
        List(a.length()) { decodeOne(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
