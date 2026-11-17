package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.maintenance.DueLevel
import fr.p308.lanceur.maintenance.History
import fr.p308.lanceur.maintenance.HistoryEntry
import fr.p308.lanceur.maintenance.MaintenanceItem
import fr.p308.lanceur.maintenance.OilWear
import fr.p308.lanceur.maintenance.due
import fr.p308.lanceur.maintenance.fmtKm
import fr.p308.lanceur.maintenance.intervalLabel
import fr.p308.lanceur.maintenance.label
import fr.p308.lanceur.maintenance.relatedHistory
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DETAIL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE)

/** Nombre d'interventions liées montrées dans la fiche (les plus récentes). */
private const val MAX_RELATED = 6

/**
 * v0.42 : fiche d'un entretien, en lecture seule (Entretien → toucher une case). Avant, toucher une case ouvrait
 * directement le formulaire de modification : impossible de simplement lire la note entière, la prochaine échéance ou
 * ce qui a déjà été fait. La fiche montre l'état, le dernier passage, l'intervalle, la prochaine échéance, la note en
 * entier et les interventions de l'historique liées ; « Modifier » ouvre le formulaire.
 */
@Composable
fun MaintenanceDetailDialog(item: MaintenanceItem, currentKm: Int?, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val history by History.entries.collectAsStateWithLifecycle()
    val related = remember(item, history) { item.relatedHistory(history) }
    val due = item.due(currentKm)
    val color = when (due.level) {
        DueLevel.LATE -> C.Red
        DueLevel.SOON -> C.Orange
        DueLevel.OK -> C.Green
        DueLevel.NOT_SET -> C.Sub
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 700.dp).padding(16.dp),
        containerColor = C.Card,
        title = { Text(item.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(due.label(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
                if (due.level != DueLevel.NOT_SET) {
                    LinearProgressIndicator(
                        progress = { due.progress }, color = color, trackColor = C.Bg,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
                Spacer(Modifier.height(2.dp))

                DetailLine("Dernière fois", when {
                    item.lastDate != null && item.lastKm != null -> "le ${item.lastDate.format(DETAIL_DATE)}, à ${fmtKm(item.lastKm)} km"
                    item.lastDate != null -> "le ${item.lastDate.format(DETAIL_DATE)}"
                    item.lastKm != null -> "à ${fmtKm(item.lastKm)} km"
                    else -> "pas encore renseigné (touche « Modifier »)"
                })
                if (item.lastKm != null && currentKm != null && currentKm >= item.lastKm) {
                    DetailLine("Depuis", "${fmtKm(currentKm - item.lastKm)} km parcourus")
                }
                DetailLine("Intervalle", item.intervalLabel())

                // Prochaine échéance : en km (comme le compte à rebours, usage sévère compris) et en date.
                val nextKm = when {
                    due.kmLeft != null && currentKm != null -> currentKm + due.kmLeft
                    item.everyKm > 0 && item.lastKm != null -> item.lastKm + item.everyKm
                    else -> null
                }
                val nextDate = if (item.everyMonths > 0 && item.lastDate != null) item.lastDate.plusMonths(item.everyMonths.toLong()) else null
                val next = listOfNotNull(
                    nextKm?.let { "vers ${fmtKm(it)} km" },
                    nextDate?.let { "le ${it.format(DETAIL_DATE)}" },
                )
                if (next.isNotEmpty()) {
                    DetailLine("Prochaine échéance", next.joinToString(" ou ") + if (next.size == 2) " (le premier des deux)" else "")
                }

                // Vidange : pourquoi elle est avancée (ou non).
                val wear = OilWear.info
                if (OilWear.isOil(item) && wear != null) {
                    DetailLine(
                        "Usage",
                        if (wear.shortPct >= 20) "sévère : ${wear.shortPct} % de trajets de moins de 10 km sur ${wear.trips} trajets, " +
                            "les km comptent × %.2f".format(Locale.FRANCE, wear.factor)
                        else "normal : ${wear.shortPct} % de petits trajets sur ${wear.trips} trajets",
                    )
                }

                if (item.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Note", fontSize = 12.sp, color = C.Sub)
                    Text(item.note, fontSize = 15.sp)
                }

                Spacer(Modifier.height(6.dp))
                Text("Dans l'historique", fontSize = 12.sp, color = C.Sub)
                if (related.isEmpty()) {
                    Text("Aucune intervention liée pour l'instant (Entretien → Historique).", fontSize = 14.sp, color = C.Sub)
                } else {
                    related.take(MAX_RELATED).forEach { h -> RelatedRow(h) }
                    if (related.size > MAX_RELATED) {
                        Text("+ ${related.size - MAX_RELATED} plus ancienne(s) dans Entretien → Historique", fontSize = 12.sp, color = C.Sub)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { TextButton(onClick = onEdit) { Text("Modifier") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, color = C.Sub, modifier = Modifier.widthIn(min = 150.dp))
        Text(value, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RelatedRow(h: HistoryEntry) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Field.copy(0.45f)).padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        val head = listOfNotNull(
            h.date?.format(SHORT_DATE) ?: h.dateNote.ifEmpty { null },
            h.km?.let { "${fmtKm(it)} km" },
            h.priceEur?.let { "%.2f €".format(Locale.FRANCE, it) },
        ).joinToString(" · ")
        if (head.isNotEmpty()) Text(head, fontSize = 12.sp, color = C.Sub)
        Text(h.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (h.details.isNotEmpty()) Text(h.details, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (h.place.isNotEmpty()) Text(h.place, fontSize = 12.sp, color = C.Sub)
    }
}
