package fr.p308.lanceur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.drive.Chrono
import fr.p308.lanceur.drive.ChronoMode
import fr.p308.lanceur.drive.ChronoPhase
import fr.p308.lanceur.drive.Trip
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

@Composable
fun TripPanel() {
    val t by Trip.state.collectAsStateWithLifecycle()
    val minutes = t.movingS / 60
    Tile(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trajet en cours", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = Trip::reset) { Text("Remettre à zéro") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f)) {
            Value("Distance", "%.1f km".format(Locale.FRANCE, t.distanceM / 1000), Modifier.weight(1f))
            Value("Temps de roulage", "%d h %02d".format(Locale.FRANCE, (minutes / 60).toInt(), (minutes % 60).toInt()), Modifier.weight(1f))
        }
        Row(Modifier.weight(1f)) {
            Value("Vitesse moyenne", "%.0f km/h".format(Locale.FRANCE, t.avgKmh), Modifier.weight(1f))
            Value("Vitesse maxi", "${t.maxKmh.toInt()} km/h", Modifier.weight(1f))
        }
        Text("Depuis le démarrage de l'autoradio. Vitesse lue sur l'OBD, sinon sur le GPS.", fontSize = 12.sp, color = C.Sub)
    }
}

@Composable
private fun Value(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = C.Sub)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
    }
}
