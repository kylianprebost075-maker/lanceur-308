package fr.p308.lanceur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.drive.PowerResult
import fr.p308.lanceur.drive.PowerRun
import fr.p308.lanceur.vehicle.CarProfile

private const val BENCH_MIN_RPM = 1000f
private const val BENCH_MAX_RPM = 5000f
private const val BENCH_MAX_CH = 130f
private const val BENCH_MAX_NM = 280f

/**
 * Onglet Moteur → Banc : puissance et couple réels mesurés à l'accélération. L'appli repère toute seule une
 * accélération à fond dans un seul rapport et en tire la courbe ; c'est une estimation (± 10-15 %), à faire sur
 * route plate, sans vent, pied au plancher jusqu'à la zone rouge en 3e ou 4e.
 */
@Composable
fun PowerBenchPanel() {
    val s by PowerRun.state.collectAsStateWithLifecycle()
    val shown = s.last ?: s.best
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1.4f).fillMaxHeight()) {
            Text("Banc de puissance", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(s.status, fontSize = 13.sp, color = if (s.collecting) C.Green else C.Sub)
            Spacer(Modifier.height(8.dp))
            if (shown == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucune mesure.\nSur une route plate et dégagée, passe la 3e ou la 4e vers 1 500 tr/min, puis accélère\n" +
                            "à fond jusqu'à la zone rouge sans changer de rapport. La courbe apparaît toute seule.",
                        fontSize = 14.sp, color = C.Sub,
                    )
                }
            } else {
                PowerCurve(shown, Modifier.weight(1f).fillMaxWidth())
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(Modifier.weight(1f).fillMaxWidth()) {
                Text("Dernière mesure", fontSize = 12.sp, color = C.Sub)
                Text(s.last?.let { "${it.peakCh} ch" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(s.last?.let { "à ${it.peakChRpm} tr/min · ${it.peakNm} Nm à ${it.peakNmRpm}" } ?: "", fontSize = 12.sp, color = C.Sub)
            }
            Tile(Modifier.weight(1f).fillMaxWidth()) {
                Text("Record", fontSize = 12.sp, color = C.Sub)
                Text(s.best?.let { "${it.peakCh} ch" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Accent)
                Text(s.best?.let { "${it.peakNm} Nm" } ?: "", fontSize = 12.sp, color = C.Sub)
            }
            Tile(Modifier.weight(1.1f).fillMaxWidth()) {
                Text("Annoncé : ${CarProfile.ENGINE_LABEL}", fontSize = 12.sp, color = C.Sub)
                Text("Estimation ± 10-15 %. Route plate, sans vent.", fontSize = 11.sp, color = C.Sub)
                Text("Chaque accélération pied au plancher est mesurée toute seule.", fontSize = 11.sp, color = C.Sub)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { PowerRun.clearAll() }, modifier = Modifier.fillMaxWidth()) { Text("Effacer les mesures", fontSize = 13.sp) }
            }
        }
    }
}

/** Courbe puissance (vert) et couple (orange) selon le régime, avec repères des pics. */
@Composable
private fun PowerCurve(r: PowerResult, modifier: Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(C.Green, "Puissance (ch)")
            Legend(C.Orange, "Couple (Nm)")
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            val w = size.width
            val h = size.height
            // Échelle haute adaptée à la mesure (v0.47) : une valeur au-delà de 130 ch ou 280 Nm n'est plus coupée net.
            val maxCh = maxOf(BENCH_MAX_CH, r.peakCh * 1.15f)
            val maxNm = maxOf(BENCH_MAX_NM, r.peakNm * 1.15f)
            // Échelle de 1 000 à 5 000 tr/min, alignée sur les graduations écrites dessous (aux deux bords et tous les 1 000).
            fun x(rpm: Int) = ((rpm - BENCH_MIN_RPM) / (BENCH_MAX_RPM - BENCH_MIN_RPM)).coerceIn(0f, 1f) * w
            fun yCh(ch: Float) = h - (ch / maxCh).coerceIn(0f, 1f) * h
            fun yNm(nm: Float) = h - (nm / maxNm).coerceIn(0f, 1f) * h
            // Repères verticaux à 2 000, 3 000 et 4 000 tr/min.
            for (k in 2..4) {
                val xv = x(k * 1000)
                drawLine(C.Bg, Offset(xv, 0f), Offset(xv, h), strokeWidth = 1.dp.toPx())
            }
            val pts = r.points
            for (i in 1 until pts.size) {
                drawLine(C.Orange, Offset(x(pts[i - 1].rpm), yNm(pts[i - 1].nm)), Offset(x(pts[i].rpm), yNm(pts[i].nm)),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            for (i in 1 until pts.size) {
                drawLine(C.Green, Offset(x(pts[i - 1].rpm), yCh(pts[i - 1].ch)), Offset(x(pts[i].rpm), yCh(pts[i].ch)),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(C.Green, 5.dp.toPx(), Offset(x(r.peakChRpm), yCh(r.peakCh.toFloat())))
            drawCircle(C.Orange, 5.dp.toPx(), Offset(x(r.peakNmRpm), yNm(r.peakNm.toFloat())))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (k in 1..5) Text("${k}000", fontSize = 10.sp, color = C.Sub)
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(3.dp).padding(end = 0.dp)) { Canvas(Modifier.fillMaxSize()) { drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3.dp.toPx()) } }
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, color = C.Sub)
    }
}
