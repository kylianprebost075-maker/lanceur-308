package fr.p308.lanceur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.backup.Backup
import fr.p308.lanceur.vehicle.GpsMonitor
import java.util.Locale

/** Diagnostic du GPS de l'autoradio, pour comprendre les gels de position sous CarPlay. */
@Composable
fun GpsPanel() {
    val g by GpsMonitor.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val (status, color) = when {
        g.fixAgeS == null -> "En attente d'une position…" to C.Sub
        g.frozen -> "GEL détecté" to C.Red
        (g.fixAgeS ?: 0f) > 3f -> "Position perdue" to C.Orange
        else -> "Position reçue" to C.Green
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Text("GPS de l'autoradio", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(status, fontSize = 18.sp, color = color, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row { Big("Satellites", g.satsUsed?.let { "$it / ${g.satsVisible}" }, Modifier.weight(1f)); Big("Précision", g.accuracyM?.let { "${it.toInt()} m" }, Modifier.weight(1f)) }
            Row { Big("Dernière position", g.fixAgeS?.let { "il y a %.0f s".format(Locale.FRANCE, it) }, Modifier.weight(1f)); Big("Vitesse GPS", g.speedKmh?.let { "${it.toInt()} km/h" }, Modifier.weight(1f)) }
            Spacer(Modifier.weight(1f))
            Text(g.lat?.let { "%.5f, %.5f".format(Locale.US, it, g.lon) } ?: "", fontSize = 12.sp, color = C.Sub)
        }
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Text("Gels détectés : ${g.freezes}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (g.freezes > 0) C.Orange else C.Text)
            Text(g.lastFreeze ?: "Aucun pour l'instant", fontSize = 14.sp, color = C.Sub)
            Spacer(Modifier.height(12.dp))
            Text(
                "Laisse tourner pendant un trajet. Si la position se fige dans Waze ou Plans, regarde ici " +
                    "si le GPS de l'autoradio s'est figé au même moment : tout est noté dans un journal.",
                fontSize = 13.sp, color = C.Sub,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { GpsMonitor.markCarPlayFreeze(); message = "Noté à ${java.time.LocalTime.now().withNano(0)}" },
                colors = ButtonDefaults.buttonColors(containerColor = C.Orange, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Waze / Plans vient de se figer", fontWeight = FontWeight.SemiBold) }
            Text("Signalements : ${g.carplayMarks}", fontSize = 12.sp, color = C.Sub)
            TextButton(onClick = { message = Backup.copyGpsLogs(ctx) }) { Text("Copier le journal sur la clé USB") }
            message?.let { Text(it, fontSize = 12.sp, color = C.Sub) }
        }
    }
}

@Composable
private fun Big(label: String, value: String?, modifier: Modifier = Modifier, color: Color = C.Text) {
    Column(modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = C.Sub)
        Text(value ?: "—", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = color)
        Spacer(Modifier.height(8.dp))
    }
}
