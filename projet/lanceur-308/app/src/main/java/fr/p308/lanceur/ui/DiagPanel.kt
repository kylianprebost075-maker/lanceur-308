package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.maintenance.fmtKm
import fr.p308.lanceur.vehicle.DtcInfo
import fr.p308.lanceur.vehicle.Engine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Onglet Moteur → Diagnostic : codes défaut normalisés (lecture, effacement confirmé) et kilométrage moteur. */
@Composable
fun DiagPanel() {
    val d by Engine.diag.collectAsStateWithLifecycle()
    val e by Engine.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val codes = buildList {
        d.stored.forEach { add(it to "Mémorisé") }
        d.pending.filter { it !in d.stored }.forEach { add(it to "En attente") }
        d.permanent.filter { it !in d.stored && it !in d.pending }.forEach { add(it to "Permanent") }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Text("Diagnostic", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (e.connected) "Boîtier OBD connecté" else e.status, fontSize = 13.sp, color = if (e.connected) C.Green else C.Sub)
            Spacer(Modifier.padding(4.dp))
            Text(d.status, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (codes.isEmpty()) C.Text else C.Orange)
            d.readAtMs?.let {
                Text("Lu à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(it)), fontSize = 12.sp, color = C.Sub)
            }
            Spacer(Modifier.padding(4.dp))
            Text("Kilométrage (calculateur moteur)", fontSize = 12.sp, color = C.Sub)
            Text(d.ecuKm?.let { "${fmtKm(it)} km" } ?: "—", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("Recopié dans l'entretien à chaque connexion.", fontSize = 11.sp, color = C.Sub)
            Spacer(Modifier.weight(1f))
            Button(onClick = { Engine.requestDiag() }, enabled = !d.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (d.busy) "Lecture…" else "Lire les codes défaut")
            }
            OutlinedButton(
                onClick = { confirmClear = true }, enabled = !d.busy && e.connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Effacer les codes…") }
        }
        Tile(Modifier.weight(1.4f).fillMaxHeight()) {
            Text("Codes défaut", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (codes.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (d.readAtMs == null) "Touche « Lire les codes défaut », contact mis." else "Aucun code : tout va bien.",
                        color = C.Sub, fontSize = 15.sp,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(codes, key = { it.first + it.second }) { (code, kind) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Field.copy(0.5f)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(code, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(84.dp))
                            Column(Modifier.weight(1f)) {
                                Text(DtcInfo.describe(code), fontSize = 14.sp)
                                Text(kind, fontSize = 11.sp, color = if (kind == "Mémorisé") C.Orange else C.Sub)
                            }
                        }
                    }
                }
            }
            Text(
                "FAP, vanne EGR et feux : non lisibles avec ce boîtier (vérifié le 08/11/2026). Huile : température non fournie par le calculateur.",
                fontSize = 11.sp, color = C.Sub,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = C.Card,
            title = { Text("Effacer les codes défaut ?") },
            text = {
                Text(
                    "Moteur coupé, contact mis. Le voyant s'éteint, mais un défaut toujours présent reviendra. " +
                        "Note les codes avant : ils seront perdus. Les tests antipollution repartent à zéro.",
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmClear = false; Engine.requestDiag(clear = true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = C.Red),
                ) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}
