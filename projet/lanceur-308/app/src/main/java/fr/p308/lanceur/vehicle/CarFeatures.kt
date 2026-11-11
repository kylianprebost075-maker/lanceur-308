package fr.p308.lanceur.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Ce que l'autoradio transmet vraiment à l'appli (v0.31), pour la page « Ce qui marche sur mon autoradio ».
 * Chaque information est notée la première fois qu'elle arrive (avec sa dernière valeur lisible) : rien n'est supposé.
 * Une information jamais vue n'est pas forcément absente : certaines n'arrivent qu'avec un geste (porte, marche
 * arrière), d'où le conseil [Feature.howToTest].
 */
enum class Feature(val title: String, val howToTest: String) {
    SERVICE("Service CAN de l'autoradio", "Autoradio FYT seulement (service com.syu.ms)"),
    CAN_BOX("Boîtier CAN identifié", "Envoyé au démarrage par le boîtier"),
    TEMP("Température extérieure", "Contact mis, attends 10 s"),
    BATTERY("Tension de la batterie", "Contact mis, attends 10 s"),
    VOLUME("Volume de l'autoradio", "Change le volume une fois"),
    DOORS("Portes et coffre", "Ouvre puis ferme une porte, contact mis"),
    REVERSE("Marche arrière", "Passe la marche arrière, contact mis"),
    RANGE("Autonomie carburant", "Moteur tournant, attends 1 min"),
}

object CarFeatures {
    private val _seen = MutableStateFlow<Map<Feature, String>>(emptyMap())
    /** Informations reçues depuis le lancement, avec leur dernière valeur pour l'écran. */
    val seen: StateFlow<Map<Feature, String>> = _seen.asStateFlow()

    fun mark(f: Feature, value: String) {
        if (_seen.value[f] != value) _seen.update { it + (f to value) }
    }

    /** Résumé « 6 sur 8 » pour l'écran et les tests. */
    fun count(seen: Map<Feature, String> = _seen.value): Pair<Int, Int> = seen.size to Feature.values().size
}
