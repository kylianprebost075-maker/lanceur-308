package fr.p308.lanceur.vehicle

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Heure remise à l'heure par le GPS. Une appli ordinaire n'a pas le droit de changer l'heure d'Android ;
 * l'horloge du Lanceur affiche donc l'heure des satellites (écart mesuré à chaque position GPS, lissé) et le fuseau
 * de Paris quand la voiture est en France métropolitaine (changement d'heure été / hiver compris), même si
 * l'autoradio est réglé sur un autre fuseau. Écart de moins de 30 s : l'heure d'Android est gardée telle quelle.
 */
object GpsClock {
    private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    @Volatile
    var offsetMs: Long = 0L
        private set

    @Volatile
    var known = false
        private set

    @Volatile
    private var zone: ZoneId? = null

    fun onLocation(loc: Location) {
        if (loc.provider != LocationManager.GPS_PROVIDER || loc.time <= 0L) return
        // Heure de la position + temps écoulé depuis qu'elle a été calculée.
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        val off = loc.time + ageMs.coerceIn(0L, 5_000L) - System.currentTimeMillis()
        offsetMs = if (!known || abs(off - offsetMs) > 120_000L) off else (offsetMs * 3 + off) / 4
        known = true
        zone = if (loc.latitude in 41.0..51.6 && loc.longitude in -5.6..9.9) PARIS else null
    }

    private fun correctionMs(): Long = if (known && abs(offsetMs) > 30_000L) offsetMs else 0L

    private fun zoneNow(): ZoneId {
        val z = zone ?: return ZoneId.systemDefault()
        val sys = ZoneId.systemDefault()
        val i = Instant.now()
        return if (sys.rules.getOffset(i) == z.rules.getOffset(i)) sys else z
    }

    fun localNow(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis() + correctionMs()), zoneNow())

    /** Texte pour les Réglages. */
    fun status(): String {
        if (!known) return "En attente du GPS (heure de l'autoradio affichée)"
        val parts = mutableListOf<String>()
        val c = correctionMs()
        if (c != 0L) {
            val min = abs(c) / 60_000
            parts += "autoradio en ${if (c > 0) "retard" else "avance"} de ${if (min > 0) "$min min" else "${abs(c) / 1000} s"}, corrigé"
        }
        if (zoneNow() != ZoneId.systemDefault()) parts += "fuseau de Paris appliqué"
        return if (parts.isEmpty()) "Heure de l'autoradio juste (vérifiée par le GPS)" else "Remise à l'heure par le GPS : " + parts.joinToString(", ")
    }
}
