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
import androidx.compose.material3.OutlinedButton
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
    var leveling by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<Float?>(null) }
    val gauge by fr.p308.lanceur.drive.TankGauge.state.collectAsStateWithLifecycle()
    val range by fr.p308.lanceur.drive.FuelRange.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Conso moyenne", stats.avgL100?.let { "%.1f L/100".format(Locale.FRANCE, it) }, Modifier.weight(1f))
            StatTile("Dernier plein", stats.lastL100?.let { "%.1f L/100".format(Locale.FRANCE, it) }, Modifier.weight(1f))
            StatTile("Coût au km", stats.costPerKm?.let { "%.3f €".format(Locale.FRANCE, it) }, Modifier.weight(1f))
            StatTile("Ce mois-ci", eur(stats.monthSpend), Modifier.weight(1f))
        }
        // Autonomie recalculée par le Lanceur depuis le dernier plein complet ou le niveau saisi (v0.33).
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // Ordinateur de bord (CAN) d'abord : il suit la vraie jauge. Sinon le calcul du Lanceur.
                val can = gauge.canKm
                val canL = gauge.liters
                if (can != null && canL != null) {
                    Text("≈ %.0f L · %d km d'autonomie".format(Locale.FRANCE, canL, can),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (can < 60) C.Orange else C.Text)
                    Text("Lu tout seul : autonomie de l'ordinateur de bord, litres déduits à %.1f L/100.".format(Locale.FRANCE, gauge.l100) +
                        (range?.let { " Calcul du Lanceur : ≈ %.0f L, ≈ %d km.".format(Locale.FRANCE, it.liters, it.km) } ?: ""),
                        fontSize = 11.sp, color = C.Sub)
                } else {
                Text(
                    range?.let { "≈ %.0f L · ≈ %d km d'autonomie".format(Locale.FRANCE, it.liters, it.km) } ?: "Réservoir : —",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = range?.let { if (it.km < 60) C.Orange else C.Text } ?: C.Sub,
                )
                Text(
                    range?.let {
                        "Jusqu'au témoin de réserve (≈ %d km jusqu'au réservoir vide). Depuis le %s, %d km parcourus, %s (%.1f L/100)."
                            .format(Locale.FRANCE, it.kmToEmpty, it.start, it.sinceKm, it.basis, it.l100)
                    } ?: if (currentKm == null) "Saisis d'abord le kilométrage (Réglages), puis un plein complet ou le niveau du réservoir."
                    else "Saisis un plein complet, ou le niveau du réservoir avec le bouton à droite.",
                    fontSize = 11.sp, color = C.Sub,
                )
                }
                // Plein repéré tout seul : litres ajoutés, et bouton pour le noter au carnet (il ne manque que le prix).
                gauge.lastFill?.let { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⛽ Dernier plein détecté : ≈ +%.0f L le %s".format(Locale.FRANCE, f.liters,
                            java.time.Instant.ofEpochMilli(f.atMs).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM à HH:mm"))),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Green, modifier = Modifier.weight(1f))
                        TextButton(onClick = { prefill = f.liters.toFloat(); adding = true }) { Text("Noter au carnet") }
                    }
                }
            }
            OutlinedButton(onClick = { leveling = true }) { Text("Niveau du réservoir") }
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
    if (leveling) TankLevelDialog(currentKm, range?.liters) { leveling = false }
    if (adding) FuelDialog(null, currentKm, prefill) { adding = false; prefill = null }
    editing?.let { FuelDialog(it, currentKm) { editing = null } }
}

/**
 * v0.33 : « il me reste à peu près tant de litres ». Le niveau saisi devient le point de départ du calcul de
 * l'autonomie (à la place du dernier plein complet) : utile sans plein complet récent, ou pour recaler le calcul
 * sur la jauge du tableau de bord.
 */
@Composable
private fun TankLevelDialog(currentKm: Int?, estimatedL: Double?, onDismiss: () -> Unit) {
    val tank = fr.p308.lanceur.drive.FuelRange.TANK_L
    val reserve = fr.p308.lanceur.drive.FuelRange.RESERVE_L
    val saved by Fuel.level.collectAsStateWithLifecycle()
    var liters by remember { mutableStateOf(estimatedL?.let { "%.0f".format(Locale.FRANCE, it) } ?: "") }
    var km by remember { mutableStateOf(currentKm?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    fun recompute() = fr.p308.lanceur.drive.FuelRange.update(
        Fuel.entries.value, fr.p308.lanceur.drive.TripLog.history.value, fr.p308.lanceur.vehicle.Vehicle.state.value.odometerKm)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 640.dp).padding(16.dp),
        containerColor = C.Card,
        title = { Text("Niveau du réservoir", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoAutoKeyboardInDialog()
                Text("Regarde la jauge du tableau de bord et touche le niveau le plus proche, ou tape les litres. " +
                    "Réservoir de %.0f L, témoin de réserve vers %.0f L.".format(Locale.FRANCE, tank, reserve), fontSize = 13.sp, color = C.Sub)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Plein" to tank, "3/4" to tank * 0.75, "1/2" to tank * 0.5, "1/4" to tank * 0.25, "Réserve" to reserve).forEach { (label, l) ->
                        OutlinedButton(onClick = { liters = "%.0f".format(Locale.FRANCE, l) }, modifier = Modifier.weight(1f)) {
                            Text(label, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(liters, { liters = it }, Modifier.weight(1f), singleLine = true, label = { Text("Litres restants") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(km, { km = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true, label = { Text("Kilométrage") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                saved?.let {
                    Text("Niveau déjà saisi : %.0f L le %s à %s km.".format(Locale.FRANCE, it.liters, it.date.format(DATE), fmtKm(it.km)),
                        fontSize = 12.sp, color = C.Sub)
                }
                error?.let { Text(it, color = C.Red) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = liters.replace(',', '.').trim().toFloatOrNull()
                val k = km.toIntOrNull()
                if (l == null || k == null || l < 0f || l > tank) {
                    error = "Vérifie les litres (0 à %.0f) et le kilométrage.".format(Locale.FRANCE, tank)
                } else {
                    Fuel.setLevel(l, k)
                    recompute()
                    onDismiss()
                }
            }) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                if (saved != null) TextButton(onClick = { Fuel.clearLevel(); recompute(); onDismiss() }) { Text("Oublier ce niveau", color = C.Red) }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

@Composable
private fun StatTile(label: String, value: String?, modifier: Modifier) {
    Tile(modifier) {
        Text(label, fontSize = 12.sp, color = C.Sub)
        Text(value ?: "—", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FuelDialog(initial: FuelEntry?, currentKm: Int?, prefillLiters: Float? = null, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf((initial?.date ?: LocalDate.now()).format(DATE)) }
    var km by remember { mutableStateOf((initial?.km ?: currentKm)?.toString() ?: "") }
    // Plein détecté tout seul : litres estimés proposés, à corriger avec le ticket de la pompe.
    var liters by remember { mutableStateOf((initial?.liters ?: prefillLiters)?.let { "%.2f".format(Locale.FRANCE, it) } ?: "") }
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
