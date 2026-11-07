package fr.p308.lanceur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.vehicle.CanProbe

/** Outil pour trouver, sans ADB, comment l'autoradio annonce l'ouverture des portes. */
@Composable
fun CanProbePanel() {
    val ctx = LocalContext.current
    val s by CanProbe.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var copyMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.events.size) { if (s.events.isNotEmpty()) list.animateScrollToItem(s.events.size - 1) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(0.8f).fillMaxHeight()) {
            Text("Diagnostic CAN", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "1. Appuie sur Démarrer (contact mis).\n2. Ouvre la porte conducteur, attends 3 s, referme.\n" +
                    "3. Pareil avec le coffre.\n4. Appuie sur Terminer et envoie-moi une photo de la liste " +
                    "(ou le fichier du rapport).",
                fontSize = 14.sp, color = C.Sub,
            )
            Spacer(Modifier.weight(1f))
            Text("Applis CAN trouvées : ${s.packages.size} · messages écoutés : ${s.listenedActions}", fontSize = 12.sp, color = C.Sub)
            s.reportFile?.let { Text("Rapport : Android/data/fr.p308.lanceur/files/diagnostic-can.txt", fontSize = 12.sp, color = C.Sub) }
            TextButton(onClick = { copyMessage = CanProbe.copyApks(ctx) }) { Text("Copier les APK CAN et le rapport sur la clé USB") }
            copyMessage?.let { Text(it, fontSize = 12.sp, color = C.Sub) }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { if (s.running) CanProbe.stop(ctx) else CanProbe.start(ctx) },
                colors = ButtonDefaults.buttonColors(containerColor = if (s.running) C.Red else C.Green, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (s.running) "Terminer" else "Démarrer", fontWeight = FontWeight.SemiBold) }
        }
        Tile(Modifier.weight(1.2f).fillMaxHeight()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(s.events) { e ->
                    Text(e, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = when { e.contains("CHANGEMENT") || e.contains("MESSAGE") || e.contains("→") -> C.Orange; e.contains("APPLI") -> C.Accent; else -> C.Sub })
                }
            }
        }
    }
}
