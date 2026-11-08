package fr.p308.lanceur.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.p308.lanceur.maintenance.fmtKm
import fr.p308.lanceur.vehicle.Engine
import fr.p308.lanceur.vehicle.ShiftAdvice
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_CH = 120f
private const val MAX_RPM = 5000f

private enum class EngineTab(val label: String) { POWER("Puissance"), DIAG("Diagnostic"), CHRONO("Chrono"), TRIP("Trajet") }

@Composable
fun EngineScreen() {
    var tab by rememberSaveable { mutableStateOf(EngineTab.POWER) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segmented(EngineTab.entries.map { it to it.label }, tab) { tab = it }
        Box(Modifier.weight(1f)) {
            when (tab) {
                EngineTab.POWER -> PowerPanel()
                EngineTab.DIAG -> DiagPanel()
                EngineTab.CHRONO -> ChronoPanel()
                EngineTab.TRIP -> TripPanel()
            }
        }
    }
}

/** Lit lui-même l'état moteur : seule cette page se redessine à chaque relevé OBD. */
@Composable
private fun PowerPanel() {
    val e by Engine.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1.2f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Puissance", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (e.connected) C.Green else C.Sub))
                        Spacer(Modifier.width(6.dp))
                        Text(e.status, fontSize = 13.sp, color = C.Sub, maxLines = 1)
                    }
                }
                if (!e.connected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { Engine.reconnectNow(ctx) }) { Text("Rechercher OBD") }
                        TextButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Bluetooth") }
                    }
                }
            }
            PowerGauge(e.powerCh, e.torqueNm, Modifier.weight(1f).fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pic : ${e.peakCh} ch", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Text(
                    listOfNotNull(e.advice.gear?.let { "Rapport ${ShiftAdvice.ordinal(it)}" }, e.advice.text.ifEmpty { null }).joinToString("  ·  "),
                    fontSize = 15.sp, color = if (e.advice.kind == ShiftAdvice.Kind.UP) C.Green else C.Orange,
                    maxLines = 1, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = Engine::resetPeak) { Text("Remettre à zéro") }
            }
            Text("Vgate vLinker MC+ : branche-le et mets le contact, la connexion BLE (vLinker MC-IOS) est automatique sans appairage.", fontSize = 11.sp, color = C.Sub)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BarTile("Régime", e.rpm?.let { "${fmtKm(it)} tr/min" }, (e.rpm ?: 0) / MAX_RPM, Modifier.weight(1f))
            BarTile("Charge moteur", e.loadPct?.let { "$it %" }, (e.loadPct ?: 0) / 100f, Modifier.weight(1f))
            Tile(Modifier.weight(1.2f).fillMaxWidth()) {
                Row(Modifier.weight(1f)) {
                    Value("Vitesse", e.speedKmh?.let { "$it km/h" }, Modifier.weight(1f))
                    Value("Turbo", e.boostBar?.let { "%.2f bar".format(Locale.FRANCE, it) }, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f)) {
                    Value("Eau", e.coolantC?.let { "$it °C" }, Modifier.weight(1f))
                    Value("Couple", e.torqueNm?.let { "$it Nm" }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Value(label: String, value: String?, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = C.Sub)
        Text(value ?: "—", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BarTile(label: String, value: String?, fraction: Float, modifier: Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(250), label = label)
    Tile(modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = C.Sub)
        Text(value ?: "—", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        LinearProgressIndicator(
            progress = { f }, color = if (f > 0.85f) C.Red else if (f > 0.6f) C.Orange else C.Green, trackColor = C.Bg,
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
    }
}

/** Cadran 0–120 ch sur 240°, dégradé vert → orange → rouge. */
@Composable
private fun PowerGauge(powerCh: Int?, torqueNm: Int?, modifier: Modifier) {
    val f by animateFloatAsState(((powerCh ?: 0) / MAX_CH).coerceIn(0f, 1f), tween(250), label = "puissance")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f).padding(8.dp)) {
            val stroke = size.minDimension * 0.08f
            val inset = stroke / 2 + size.minDimension * 0.06f
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = Offset(inset, inset)
            drawArc(C.Bg, 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(0f to lerp(C.Orange, C.Red, 0.75f), 0.0833f to C.Red, 0.4167f to C.Green, 0.75f to C.Orange, 1f to lerp(C.Orange, C.Red, 0.75f), center = center),
                150f, 240f * f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val r = size.minDimension / 2
            for (i in 0..12) {
                val a = Math.toRadians(150.0 + 240.0 * i / 12)
                val r1 = r * (if (i % 2 == 0) 0.70f else 0.74f)
                val r2 = r * 0.78f
                drawLine(
                    Color.White.copy(if (i % 2 == 0) 0.6f else 0.3f),
                    Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat()),
                    Offset(center.x + r2 * cos(a).toFloat(), center.y + r2 * sin(a).toFloat()),
                    strokeWidth = 3f,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(powerCh?.toString() ?: "—", fontSize = 64.sp, fontWeight = FontWeight.Light)
            Text("ch", fontSize = 18.sp, color = C.Sub)
            Text(torqueNm?.let { "$it Nm" } ?: "", fontSize = 15.sp, color = C.Sub)
        }
    }
}
