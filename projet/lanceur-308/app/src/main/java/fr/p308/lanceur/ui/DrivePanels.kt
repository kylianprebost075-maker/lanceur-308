package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.drive.Chrono
import fr.p308.lanceur.drive.ChronoMode
import fr.p308.lanceur.drive.ChronoPhase
import fr.p308.lanceur.drive.Trip
import fr.p308.lanceur.drive.TripLog
import fr.p308.lanceur.drive.TripRecord
import fr.p308.lanceur.vehicle.Vehicle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun secs(s: Float) = "%.2f s".format(Locale.FRANCE, s)

@Composable
fun ChronoPanel() {
    val c by Chrono.state.collectAsStateWithLifecycle()
    val trip by Trip.state.collectAsStateWithLifecycle()
    val (hint, color) = when (c.phase) {
        ChronoPhase.WAITING -> (if (c.mode == ChronoMode.ZERO_100) "Arrête-toi pour armer le chrono" else "Roule entre 40 et 78 km/h pour l'armer") to C.Sub
        ChronoPhase.READY -> (if (c.mode == ChronoMode.ZERO_100) "Prêt : le chrono part au démarrage" else "Prêt : le chrono part à 80 km/h") to C.Green
        ChronoPhase.RUNNING -> "En cours…" to C.Orange
        ChronoPhase.DONE -> "Terminé" to C.Green
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1.3f).fillMaxHeight()) {
            Segmented(ChronoMode.entries.map { it to it.label }, c.mode) { Chrono.select(it) }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (c.phase == ChronoPhase.WAITING && c.resultS == null) "—" else secs(c.elapsedS), fontSize = 72.sp, fontWeight = FontWeight.Light)
                Text(hint, fontSize = 18.sp, color = color)
                Text("${trip.currentKmh.toInt()} km/h", fontSize = 16.sp, color = C.Sub)
            }
            Spacer(Modifier.weight(1f))
            Text("À n'utiliser qu'en toute sécurité, sur route dégagée et sans regarder l'écran.", fontSize = 11.sp, color = C.Sub)
        }
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Text("Records", fontSize = 13.sp, color = C.Sub)
            ChronoMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(m.label, Modifier.weight(1f), fontSize = 16.sp)
                    Text(c.best[m]?.let(::secs) ?: "—", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Vitesse lue sur l'OBD si l'adaptateur est branché (plus précis), sinon sur le GPS.", fontSize = 12.sp, color = C.Sub)
            TextButton(onClick = Chrono::resetBest) { Text("Effacer les records") }
        }
    }
}

private fun duration(s: Double): String {
    val m = (s / 60).toInt()
    return if (m < 60) "$m min" else "%d h %02d".format(Locale.FRANCE, m / 60, m % 60)
}

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> C.Green
    score >= 60 -> C.Orange
    else -> C.Red
}

/** Onglet Moteur → Trajets : trajet en cours avec son score de conduite souple, puis l'historique. */
@Composable
fun TripPanel() {
    val cur by TripLog.current.collectAsStateWithLifecycle()
    val stored by TripLog.history.collectAsStateWithLifecycle()
    val list = if (Vehicle.demo && stored.isEmpty()) TripLog.demoHistory() else stored
    var open by remember { mutableStateOf<TripRecord?>(null) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trajet en cours", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (cur != null) TextButton(onClick = TripLog::finishNow) { Text("Terminer") }
            }
            val t = cur
            if (t == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Démarre dès 5 km/h, se termine après 5 min à l'arrêt.", color = C.Sub, fontSize = 15.sp)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Value("Distance", "%.1f km".format(Locale.FRANCE, t.distanceKm), Modifier.weight(1f))
                    Value("Roulage", duration(t.movingS), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Value("Moyenne", "%.0f km/h".format(Locale.FRANCE, t.avgKmh), Modifier.weight(1f))
                    Value("Conso (L/100 km)", t.l100?.let { "≈ %.1f".format(Locale.FRANCE, it) } ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.weight(1f))
                ScoreDetail(t)
            }
            Text("Vitesse lue sur l'OBD, sinon sur le GPS. Le régime et l'eau comptent seulement avec l'OBD.", fontSize = 11.sp, color = C.Sub)
        }
        Tile(Modifier.weight(1.15f).fillMaxHeight()) {
            val week = list.filter { System.currentTimeMillis() - it.startMs < 7 * 86_400_000L }
            Text("Derniers trajets", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (week.isNotEmpty()) {
                Text(
                    "7 derniers jours : %.0f km · %d trajet(s) · score moyen %d".format(
                        Locale.FRANCE, week.sumOf { it.distanceKm }, week.size, week.map { it.score }.average().toInt(),
                    ),
                    fontSize = 13.sp, color = C.Sub,
                )
            }
            Spacer(Modifier.height(6.dp))
            if (list.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Aucun trajet enregistré pour l'instant.", color = C.Sub, fontSize = 15.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.startMs }) { t -> TripRow(t) { open = t } }
                }
            }
        }
    }

    open?.let { t ->
        AlertDialog(
            onDismissRequest = { open = null },
            containerColor = C.Card,
            title = { Text(SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.FRANCE).format(Date(t.startMs))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("%.1f km en %s · moyenne %.0f km/h · maxi %d km/h".format(
                        Locale.FRANCE, t.distanceKm, duration(t.movingS), t.avgKmh, t.maxKmh.toInt()), fontSize = 15.sp)
                    if (t.peakCh > 0) Text("Puissance maxi utilisée : ${t.peakCh} ch", fontSize = 15.sp)
                    t.fuelL?.let { l ->
                        val price = fr.p308.lanceur.drive.MonthReport.lastPricePerL(fr.p308.lanceur.maintenance.Fuel.entries.value)
                        Text("Gazole estimé : ≈ %.1f L".format(Locale.FRANCE, l) +
                            (t.l100?.let { " (%.1f L/100)".format(Locale.FRANCE, it) } ?: "") +
                            (price?.let { " · ≈ %.2f €".format(Locale.FRANCE, l * it) } ?: ""), fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    ScoreDetail(t)
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Fermer") } },
            dismissButton = if (stored.any { it.startMs == t.startMs }) {
                { TextButton(onClick = { TripLog.delete(t.startMs); open = null }) { Text("Supprimer", color = C.Red) } }
            } else null,
        )
    }
}

/** Score et ce qui l'a fait baisser, avec un conseil par point. */
@Composable
private fun ScoreDetail(t: TripRecord) {
    val s = t.score
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$s", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = scoreColor(s))
        Text(" / 100  conduite souple", fontSize = 15.sp, color = C.Sub)
    }
    val p = TripLog.penalties(t)
    Text("Retiré : −${p.harsh} à-coups (sur 40) · −${p.shifting} passages (sur 30) · −${p.cold} moteur froid (sur 20)",
        fontSize = 12.sp, color = C.Sub)
    val brusque = t.harshAccel + t.harshBrake
    Text(
        if (brusque == 0) "Aucune accélération ni freinage brusque"
        else "$brusque à-coup(s) : ${t.harshAccel} accélération(s), ${t.harshBrake} freinage(s) — anticipe davantage",
        fontSize = 13.sp, color = if (brusque == 0) C.Sub else C.Orange,
    )
    if (t.shifts > 0) {
        Text(
            "${t.shifts} montée(s) de rapport, ${t.goodShiftPct} % bien placées" +
                (t.avgShiftRpm?.let { " (en moyenne à ${fr.p308.lanceur.maintenance.fmtKm(it)} tr/min)" } ?: ""),
            fontSize = 13.sp, color = if (t.goodShiftPct >= 80) C.Sub else C.Orange,
        )
        if (t.lateShifts > 0) Text("${t.lateShifts} trop tard : monte vers 2 000–2 500 tr/min quand tu accélères doucement",
            fontSize = 13.sp, color = C.Orange)
        if (t.earlyShifts > 0) Text("${t.earlyShifts} trop tôt : le moteur tombait sous 1 150 tr/min, il peine et encrasse",
            fontSize = 13.sp, color = C.Orange)
    } else if (t.shifts == 0 && t.obdS >= 60) {
        Text("Aucune montée de rapport repérée (rapports en cours d'apprentissage ?)", fontSize = 13.sp, color = C.Sub)
    }
    if (t.obdS >= 60) {
        if (t.overRevPct > 3) Text("${t.overRevPct} % du temps en régime trop haut malgré ▲ : passe la vitesse quand il s'affiche",
            fontSize = 13.sp, color = C.Orange)
        if (t.lugPct > 2) Text("${t.lugPct} % du temps moteur qui peine : rétrograde quand ▼ s'affiche",
            fontSize = 13.sp, color = C.Orange)
        if (t.shifts < 0 && t.overRevPct <= 3) Text("Rapports bien passés", fontSize = 13.sp, color = C.Sub)
    }
    if (t.coldRevS > 0) {
        Text("Moteur froid poussé ${t.coldRevS} s au-dessus de 2 800 tr/min — attends l'eau et l'huile à 60 °C",
            fontSize = 13.sp, color = C.Orange)
    }
}

@Composable
private fun TripRow(t: TripRecord, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Field.copy(0.5f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(SimpleDateFormat("EEE d MMM · HH:mm", Locale.FRANCE).format(Date(t.startMs)), fontSize = 14.sp)
            Text("%.1f km · %s · %.0f km/h".format(Locale.FRANCE, t.distanceKm, duration(t.movingS), t.avgKmh) +
                (t.l100?.let { " · ≈ %.1f L/100".format(Locale.FRANCE, it) } ?: ""),
                fontSize = 12.sp, color = C.Sub)
        }
        Text("${t.score}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = scoreColor(t.score))
    }
}

@Composable
private fun Value(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = C.Sub)
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}
