package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.drive.FuelRange
import fr.p308.lanceur.drive.ReadyCheck
import fr.p308.lanceur.drive.ReadyLevel
import fr.p308.lanceur.drive.ReadyLine
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.maintenance.due
import fr.p308.lanceur.vehicle.BatteryWatch
import fr.p308.lanceur.vehicle.CrankWatch
import fr.p308.lanceur.vehicle.Vehicle
import kotlinx.coroutines.delay

/** Les lignes du bilan « Prêt à partir » d'après ce que l'appli sait à cet instant. */
fun readyLinesNow(): List<ReadyLine> {
    val vs = Vehicle.state.value
    return ReadyCheck.lines(
        outsideC = vs.outsideTempC, outsideEstimated = vs.outsideTempEstimated,
        battery = BatteryWatch.state.value,
        lastCrank = CrankWatch.history.value.maxByOrNull { it.atMs },
        // L'autonomie de l'ordinateur de bord (CAN) si l'autoradio la donne, sinon celle calculée d'après les pleins.
        range = vs.fuelRangeKm?.let { fr.p308.lanceur.drive.RangeInfo(it, 0.0, 0.0, "ordinateur de bord") } ?: FuelRange.state.value,
        dues = Maintenance.items.value.map { it.name to it.due(vs.odometerKm) },
    )
}

/**
 * Bilan « Prêt à partir » (v0.26) : affiché au lancement, il se ferme tout seul au bout de [autoCloseS] secondes
 * (0 = reste ouvert, quand on l'ouvre depuis les Réglages). Les lignes se mettent à jour si une donnée arrive.
 */
@Composable
fun ReadyDialog(autoCloseS: Int, onDismiss: () -> Unit) {
    // Relu quand une source change : véhicule (température, kilométrage), batterie, autonomie, entretien.
    val vs by Vehicle.state.collectAsStateWithLifecycle()
    val battery by BatteryWatch.state.collectAsStateWithLifecycle()
    val range by FuelRange.state.collectAsStateWithLifecycle()
    val items by Maintenance.items.collectAsStateWithLifecycle()
    val cranks by CrankWatch.history.collectAsStateWithLifecycle()
    val lines = run { vs; battery; range; items; cranks; readyLinesNow() }
    if (autoCloseS > 0) LaunchedEffect(Unit) { delay(autoCloseS * 1000L); onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 620.dp).padding(16.dp),
        containerColor = C.Card,
        title = { Text(ReadyCheck.title(lines), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lines.isEmpty()) Text(
                    "Pas encore de données : elles arrivent avec le boîtier OBD, les pleins et les dates d'entretien.",
                    fontSize = 14.sp, color = C.Sub,
                )
                lines.forEach { l ->
                    val color = when (l.level) { ReadyLevel.OK -> C.Green; ReadyLevel.WARN -> C.Orange; ReadyLevel.ALERT -> C.Red }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(12.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(l.title, fontSize = 12.sp, color = C.Sub)
                            Text(l.text, fontSize = 16.sp, color = if (l.level == ReadyLevel.OK) C.Text else color)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
    )
}
