package fr.p308.lanceur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.p308.lanceur.drive.TripRecord
import fr.p308.lanceur.vehicle.GpsMonitor
import fr.p308.lanceur.vehicle.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/** Point du tracé : position et vitesse (km/h). */
private class TracePoint(val lat: Double, val lon: Double, val kmh: Float)

/**
 * Relit les journaux GPS du ou des jours du trajet et garde les positions prises entre le départ et l'arrivée
 * (une par seconde au plus). Lecture sur le fil d'arrière-plan.
 */
private fun loadTrace(t: TripRecord): List<TracePoint> {
    if (Vehicle.demo) return demoTrace()
    val dir = runCatching { GpsMonitor.dir }.getOrNull() ?: return emptyList()
    val zone = ZoneId.systemDefault()
    val from = Instant.ofEpochMilli(t.startMs - 30_000).atZone(zone).toLocalDateTime()
    val to = Instant.ofEpochMilli(t.endMs + 30_000).atZone(zone).toLocalDateTime()
    val days = generateSequence(from.toLocalDate()) { it.plusDays(1) }.takeWhile { !it.isAfter(to.toLocalDate()) }
    val out = ArrayList<TracePoint>()
    days.forEach { day ->
        val f = File(dir, "$day.csv")
        if (!f.exists()) return@forEach
        f.forEachLine { line ->
            val p = line.split(';')
            if (p.size < 6 || p[1] != "POSITION") return@forEachLine
            val time = runCatching { LocalDateTime.parse(p[0]) }.getOrNull() ?: return@forEachLine
            if (time.isBefore(from) || time.isAfter(to)) return@forEachLine
            val lat = p[2].toDoubleOrNull() ?: return@forEachLine
            val lon = p[3].toDoubleOrNull() ?: return@forEachLine
            out += TracePoint(lat, lon, p[5].toFloatOrNull() ?: 0f)
        }
    }
    return out
}

/** Boucle d'exemple pour le mode démo. */
private fun demoTrace(): List<TracePoint> = List(240) { i ->
    val a = i / 240.0 * 2 * Math.PI
    TracePoint(48.80 + 0.03 * sin(a) + 0.008 * sin(3 * a), 2.35 + 0.05 * cos(a), (35 + 60 * (0.5 + 0.5 * sin(2 * a))).toFloat())
}

private fun speedColor(kmh: Float): Color = when {
    kmh < 50f -> C.Accent
    kmh < 90f -> C.Green
    else -> C.Orange
}

/** Tracé du trajet (sans fond de carte : fonctionne sans Internet), coloré selon la vitesse. */
@Composable
fun TripTrace(t: TripRecord, modifier: Modifier = Modifier) {
    val points by produceState<List<TracePoint>?>(null, t.startMs) {
        value = withContext(Dispatchers.IO) { runCatching { loadTrace(t) }.getOrDefault(emptyList()) }
    }
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)).background(C.Bg.copy(0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            val pts = points
            when {
                pts == null -> Text("Chargement du tracé…", fontSize = 13.sp, color = C.Sub)
                pts.size < 2 -> Text("Pas de tracé GPS pour ce trajet", fontSize = 13.sp, color = C.Sub)
                else -> Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                    // Projection simple : longitude resserrée selon la latitude, même échelle sur les deux axes.
                    val k = cos(Math.toRadians(pts.sumOf { it.lat } / pts.size))
                    val xs = pts.map { it.lon * k }
                    val ys = pts.map { it.lat }
                    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
                    val span = maxOf(maxX - minX, maxY - minY, 1e-6)
                    val scale = minOf(size.width, size.height) / span
                    val ox = (size.width - (maxX - minX) * scale) / 2
                    val oy = (size.height - (maxY - minY) * scale) / 2
                    fun at(i: Int) = Offset(
                        (ox + (xs[i] - minX) * scale).toFloat(),
                        (size.height - oy - (ys[i] - minY) * scale).toFloat(),
                    )
                    for (i in 1 until pts.size) {
                        drawLine(speedColor(pts[i].kmh), at(i - 1), at(i), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                    }
                    drawCircle(C.Green, 6.dp.toPx(), at(0))
                    drawCircle(Color.White, 6.dp.toPx(), at(pts.size - 1))
                    drawCircle(C.Red, 4.dp.toPx(), at(pts.size - 1))
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(C.Accent, "< 50 km/h")
            Legend(C.Green, "50 à 90")
            Legend(C.Orange, "> 90")
            Text("● départ  ● arrivée", fontSize = 11.sp, color = C.Sub)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text(" $label", fontSize = 11.sp, color = C.Sub)
    }
}
