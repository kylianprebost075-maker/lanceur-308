package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Température extérieure lue dans le boîtier CAN de l'autoradio (v0.17).
 *
 * Relevé du 09/11 : l'ancienne estimation par l'air d'admission donnait 39 °C en novembre (moteur encore chaud).
 * Elle est supprimée : la température ne vient plus jamais du moteur. L'autoradio, lui, l'affiche : elle passe par
 * son service CAN (com.syu.ms), mais le numéro du canal dépend du boîtier et n'est écrit nulle part.
 * L'appli l'apprend : on tape la température affichée par l'autoradio, elle cherche quels canaux donnent cette
 * valeur (avec les codages courants des boîtiers). S'il en reste plusieurs, on recommence quand la température a
 * changé : seuls les canaux qui suivent restent. Le canal trouvé est gardé (préférences « can », sauvegardées).
 */
object OutsideTemp {
    /** Codages courants : valeur brute → °C. */
    private val FORMULAS: List<Pair<String, (Int) -> Float>> = listOf(
        "v" to { v -> v.toFloat() },
        "v/2" to { v -> v / 2f },
        "v/10" to { v -> v / 10f },
        "v-40" to { v -> v - 40f },
        "v/2-40" to { v -> v / 2f - 40f },
        "s8" to { v -> (if (v in 128..255) v - 256 else v).toFloat() },
        "s8/2" to { v -> (if (v in 128..255) v - 256 else v) / 2f },
        "s16/10" to { v -> (if (v in 32768..65535) v - 65536 else v) / 10f },
    )

    data class Status(val found: String? = null, val candidates: Int = 0, val message: String = "")

    private var prefs: SharedPreferences? = null
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Dernières valeurs reçues du service CAN : « module:canal#rang » → valeur brute (4 premiers entiers). */
    private val values = HashMap<String, Int>()

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("can", Context.MODE_PRIVATE)
        prefs = p
        _status.value = Status(found = p.getString("tempKey", null)?.let { "$it ${p.getString("tempFormula", "")}" },
            candidates = p.getStringSet("tempCandidates", emptySet())!!.size)
    }

    /** Appelé par CanBusSource pour chaque valeur reçue. Renvoie la température si c'est le canal appris. */
    fun onValue(module: Int, code: Int, ints: IntArray): Float? {
        var out: Float? = null
        val p = prefs
        val learned = p?.getString("tempKey", null)
        ints.take(4).forEachIndexed { i, v ->
            val key = "$module:$code#$i"
            synchronized(values) { values[key] = v }
            if (key == learned) {
                val f = FORMULAS.firstOrNull { it.first == p.getString("tempFormula", null) }?.second
                out = f?.invoke(v)?.takeIf { it in -40f..60f }
            }
        }
        return out
    }

    /** Nombre de canaux reçus (pour dire si le service CAN parle). */
    fun channelCount(): Int = synchronized(values) { values.size }

    /**
     * L'utilisateur donne la température affichée par l'autoradio. Garde les couples (canal, codage) qui la
     * donnent à 0,5 °C près ; s'il y en avait déjà, ne garde que ceux qui conviennent encore.
     */
    fun learn(displayedC: Int): Status {
        val p = prefs ?: return Status(message = "Pas prêt, réessaie dans quelques secondes")
        val snapshot = synchronized(values) { HashMap(values) }
        if (snapshot.isEmpty()) {
            return Status(message = "Le service CAN de l'autoradio n'envoie rien pour l'instant : mets le contact, attends 10 s et réessaie")
                .also { _status.value = it.copy(candidates = _status.value.candidates) }
        }
        val matches = snapshot.flatMap { (key, v) ->
            FORMULAS.filter { (_, f) -> abs(f(v) - displayedC) <= 0.5f }.map { "$key ${it.first}" }
        }.toSet()
        val previous = p.getStringSet("tempCandidates", emptySet())!!
        val kept = if (previous.isEmpty()) matches else previous.intersect(matches)
        val st = when {
            kept.size == 1 -> {
                val (key, formula) = kept.first().split(' ')
                p.edit().putString("tempKey", key).putString("tempFormula", formula).remove("tempCandidates").apply()
                Status(found = kept.first(), message = "Trouvé : la température extérieure vient du canal $key. Elle s'affiche maintenant partout.")
            }
            kept.isEmpty() && previous.isNotEmpty() -> {
                p.edit().remove("tempCandidates").apply()
                Status(message = "Aucun canal ne suit la température : on recommence de zéro. Vérifie le chiffre et réessaie.")
            }
            kept.isEmpty() -> Status(message = "Aucun canal ne donne $displayedC °C. Vérifie le chiffre affiché par l'autoradio et réessaie.")
            else -> {
                p.edit().putStringSet("tempCandidates", kept).apply()
                Status(candidates = kept.size,
                    message = "${kept.size} canaux possibles. Recommence quand la température affichée aura changé (un autre jour ou à un autre moment) : il n'en restera qu'un.")
            }
        }
        _status.value = st
        return st
    }

    /** Oublie le canal appris (si la température affichée est fausse). */
    fun forget() {
        prefs?.edit()?.remove("tempKey")?.remove("tempFormula")?.remove("tempCandidates")?.apply()
        _status.value = Status(message = "Canal oublié")
        Vehicle.update { it.copy(outsideTempC = null, outsideTempEstimated = false) }
    }

}
