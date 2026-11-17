package fr.p308.lanceur.maintenance

import java.text.Normalizer
import java.util.Locale

/**
 * v0.42 : interventions de l'historique liées à un entretien, pour la vue détail (Entretien → toucher une case).
 *
 * Rien ne relie les deux dans les données : le rapprochement se fait par les mots. Le nom de l'entretien est coupé en
 * parties (« Vidange + filtre à huile » → « vidange » et « filtre huile ») ; une intervention est liée si son titre ou
 * son détail contient tous les mots d'une partie, en mots entiers, pluriel compris. « Liquide de frein » ne ramène
 * donc pas « Freins : disques et plaquettes » (il manque « liquide »), et « Filtre à air » retrouve « filtres à huile,
 * à air et à gasoil ». Calcul pur, vérifié par RelatedHistoryTest.
 */
private val STOP_WORDS = setOf("de", "du", "des", "la", "le", "les", "et", "a", "au", "aux", "en", "sur", "pour", "avec", "ou")

/** Minuscules, sans accents, ponctuation remplacée par des espaces. */
internal fun normalizeWords(s: String): String =
    Normalizer.normalize(s.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

/** Parties du nom, chacune réduite à ses mots utiles (3 lettres au moins, hors mots de liaison). */
internal fun MaintenanceItem.matchParts(): List<List<String>> =
    name.split('+', '(', ')', ',', '/', ';')
        .map { part -> normalizeWords(part).split(' ').filter { it.length >= 3 && it !in STOP_WORDS } }
        .filter { it.isNotEmpty() }

/** Interventions liées à cet entretien, dans l'ordre de la liste reçue (la plus récente d'abord dans l'historique). */
fun MaintenanceItem.relatedHistory(entries: List<HistoryEntry>): List<HistoryEntry> {
    val parts = matchParts()
    if (parts.isEmpty()) return emptyList()
    return entries.filter { h ->
        val words = normalizeWords(h.title + " " + h.details).split(' ').toSet()
        fun has(w: String) = w in words || "${w}s" in words || (w.endsWith("s") && w.dropLast(1) in words)
        parts.any { part -> part.all(::has) }
    }
}
