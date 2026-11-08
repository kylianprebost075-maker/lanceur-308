package fr.p308.lanceur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.maintenance.Fuel
import fr.p308.lanceur.maintenance.FuelEntry
import fr.p308.lanceur.maintenance.fmtKm
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun eur(v: Float) = "%.2f €".format(Locale.FRANCE, v)

@Composable
fun FuelPanel(currentKm: Int?) {
    val entries by Fuel.entries.collectAsStateWithLifecycle()
    val stats = remember(entries) { Fuel.stats(entries) }
    var editing by remember { mutableStateOf<FuelEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val range by fr.p308.lanceur.drive.FuelRange.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Conso moyenne", stats.avgL100?.let { "%.1f L/100".format(Locale.FRANCE, it) }, Modifier.weight(1f))
            StatTile("Dernier plein", stats.lastL100?.let { "%.1f L/100".format(Locale.FRANCE, it) }, Modifier.weight(1f))
            StatTile("Coût au km", stats.costPerKm?.let { "%.3f €".format(Locale.FRANCE, it) }, Modifier.weight(1f))
            StatTile("Ce mois-ci", eur(stats.monthSpend), Modifier.weight(1f))
        }
        // Autonomie estimée depuis le dernier plein complet, jusqu'au témoin de réserve.
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(
                range?.let { "Autonomie ≈ ${it.km} km" } ?: "Autonomie : —",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = range?.let { if (it.km < 60) C.Orange else C.Text } ?: C.Sub,
            )
            Text(
                range?.let { "Jusqu'au témoin de réserve. ≈ %.0f L dans le réservoir, %s (%.1f L/100).".format(Locale.FRANCE, it.liters, it.basis, it.l100) }
                    ?: "Saisis un plein complet (avec le kilométrage) pour qu'elle soit estimée.",
                fontSize = 11.sp, color = C.Sub,
            )
        }
        Tile(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { adding = true }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = C.Accent)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Ajouter un plein", fontWeight = FontWeight.SemiBold)
                            Text("La conso se calcule entre deux pleins complets", fontSize = 12.sp, color = C.Sub)
                        }
                    }
                }
                items(entries.reversed(), key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Field.copy(0.45f)).clickable { editing = e }.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.date.format(DATE)} · ${fmtKm(e.km)} km", fontWeight = FontWeight.SemiBold)
                            Text(if (e.full) "Plein complet" else "Plein partiel", fontSize = 12.sp, color = C.Sub)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("%.2f L · %s".format(Locale.FRANCE, e.liters, eur(e.price)), fontWeight = FontWeight.SemiBold)
                            Text("%.3f €/L".format(Locale.FRANCE, e.pricePerL), fontSize = 12.sp, color = C.Sub)
                        }
                    }
                }
            }
        }
    }
    if (adding) FuelDialog(null, currentKm) { adding = false }
    editing?.let { FuelDialog(it, currentKm) { editing = null } }
}

@Composable
private fun StatTile(label: String, value: String?, modifier: Modifier) {
    Tile(modifier) {
        Text(label, fontSize = 12.sp, color = C.Sub)
        Text(value ?: "—", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FuelDialog(initial: FuelEntry?, currentKm: Int?, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf((initial?.date ?: LocalDate.now()).format(DATE)) }
    var km by remember { mutableStateOf((initial?.km ?: currentKm)?.toString() ?: "") }
    var liters by remember { mutableStateOf(initial?.liters?.let { "%.2f".format(Locale.FRANCE, it) } ?: "") }
    var price by remember { mutableStateOf(initial?.price?.let { "%.2f".format(Locale.FRANCE, it) } ?: "") }
    var full by remember { mutableStateOf(initial?.full ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun num(s: String) = s.replace(',', '.').trim().toFloatOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 640.dp).padding(16.dp),
        containerColor = C.Card,
        title = { Text(if (initial == null) "Nouveau plein" else "Plein du ${initial.date.format(DATE)}", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoAutoKeyboardInDialog()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(date, { date = it }, Modifier.weight(1f), singleLine = true, label = { Text("Date (jj/mm/aaaa)") })
                    OutlinedTextField(km, { km = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true, label = { Text("Kilométrage") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(liters, { liters = it }, Modifier.weight(1f), singleLine = true, label = { Text("Litres") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(price, { price = it }, Modifier.weight(1f), singleLine = true, label = { Text("Prix total (€)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Plein complet")
                        Text("Réservoir rempli jusqu'à la coupure du pistolet", fontSize = 12.sp, color = C.Sub)
                    }
                    Switch(full, { full = it })
                }
                error?.let { Text(it, color = C.Red) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = runCatching { LocalDate.parse(date.trim(), DATE) }.getOrNull()
                val k = km.toIntOrNull()
                val l = num(liters)
                val p = num(price)
                if (d == null || k == null || l == null || p == null || l <= 0f) {
                    error = "Vérifie la date, le kilométrage, les litres et le prix."
                } else {
                    Fuel.add((initial ?: FuelEntry(date = d, km = k, liters = l, price = p)).copy(date = d, km = k, liters = l, price = p, full = full))
                    onDismiss()
                }
            }) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { Fuel.delete(initial.id); onDismiss() }) { Text("Supprimer", color = C.Red) }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}
