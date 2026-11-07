package fr.p308.lanceur.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.ZonedDateTime
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

enum class NightMode(val label: String) { AUTO("Auto"), DAY("Jour"), NIGHT("Nuit") }

/** Thème nuit : forcé, ou automatique (phares allumés si le CAN le dit, sinon coucher du soleil). */
object Night {
    var active by mutableStateOf(false)
        private set

    fun refresh(mode: NightMode, headlights: Boolean?, lat: Double?, lon: Double?, now: ZonedDateTime = ZonedDateTime.now()) {
        active = when (mode) {
            NightMode.DAY -> false
            NightMode.NIGHT -> true
            NightMode.AUTO -> Sun.isNight(lat ?: 48.85, lon ?: 2.35, now)
        }
    }
}

/** Lever et coucher du soleil (algorithme NOAA simplifié, précis à quelques minutes). */
object Sun {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double) = ((v % m) + m) % m

    /** Heure UTC (en heures décimales) du lever ou du coucher, ou null s'il n'a pas lieu ce jour-là. */
    fun utcHour(lat: Double, lon: Double, dayOfYear: Int, rising: Boolean): Double? {
        val lngHour = lon / 15
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra = (ra + floor(l / 90) * 90 - floor(ra / 90) * 90) / 15
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
        return norm(h + ra - 0.06571 * t - 6.622 - lngHour, 24.0)
    }

    fun isNight(lat: Double, lon: Double, now: ZonedDateTime): Boolean {
        val utc = now.withZoneSameInstant(ZoneOffset.UTC)
        val hour = utc.hour + utc.minute / 60.0
        val rise = utcHour(lat, lon, utc.dayOfYear, true) ?: return false
        val set = utcHour(lat, lon, utc.dayOfYear, false) ?: return false
        return if (rise < set) hour < rise || hour > set else hour in set..rise
    }
}
