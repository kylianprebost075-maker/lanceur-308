package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.maintenance.History
import fr.p308.lanceur.maintenance.HistoryEntry
import fr.p308.lanceur.maintenance.fmtKm
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HIST_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE)

private fun HistoryEntry.whenText() = date?.format(HIST_DATE) ?: dateNote.ifEmpty { "date inconnue" }

/** Entretien → Historique : interventions passées (importées depuis les factures), la plus récente en haut. */
@Composable
fun HistoryPanel() {
    val list by History.entries.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<HistoryEntry?>(null) }
    Tile(Modifier.fillMaxSize()) {
        Text("Historique", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val total = list.mapNotNull { it.priceEur }.sum()
        Text(
            if (list.isEmpty()) "Interventions passées : factures, contrôles techniques, entretiens faits soi-même"
            else "%d intervention(s) · %.0f € de factures notées".format(Locale.FRANCE, list.size, total),
            fontSize = 13.sp, color = C.Sub,
        )
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Rien pour l'instant. Les factures préparées sur le PC arrivent toutes seules : fichier " +
                        "« import-entretien.json » sur la clé USB, dans Android/data/fr.p308.lanceur/files/.",
                    color = C.Sub, fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { h -> HistoryRow(h) { open = h } }
            }
        }
    }
    open?.let { h ->
        AlertDialog(
            onDismissRequest = { open = null },
            containerColor = C.Card,
            title = { Text(h.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(h.whenText() + (h.km?.let { " · ${fmtKm(it)} km" } ?: ""), fontSize = 15.sp)
                    if (h.place.isNotEmpty()) Text(h.place, fontSize = 15.sp, color = C.Sub)
                    if (h.details.isNotEmpty()) Text(h.details, fontSize = 15.sp)
                    h.priceEur?.let { Text("%.2f €".format(Locale.FRANCE, it), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Fermer") } },
            dismissButton = { TextButton(onClick = { History.delete(h.id); open = null }) { Text("Supprimer", color = C.Red) } },
        )
    }
}

@Composable
private fun HistoryRow(h: HistoryEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Field.copy(0.45f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(104.dp)) {
            Text(h.whenText(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(h.km?.let { "${fmtKm(it)} km" } ?: "", fontSize = 12.sp, color = C.Sub, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(h.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(h.details, h.place).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        h.priceEur?.let { Text("%.0f €".format(Locale.FRANCE, it), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}
