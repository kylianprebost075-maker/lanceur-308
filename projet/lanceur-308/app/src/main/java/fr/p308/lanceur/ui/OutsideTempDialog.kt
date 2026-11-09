package fr.p308.lanceur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.p308.lanceur.vehicle.OutsideTemp
import fr.p308.lanceur.vehicle.Vehicle

/**
 * Réglages → Température extérieure : apprend le canal CAN de l'autoradio qui la donne. On recopie la température
 * affichée par l'autoradio (barre du haut ou écran CAN), l'appli cherche le canal correspondant.
 */
@Composable
fun OutsideTempDialog(onDismiss: () -> Unit) {
    val st by OutsideTemp.status.collectAsStateValue()
    val vs by Vehicle.state.collectAsStateValue()
    var text by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text("Température extérieure") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoAutoKeyboardInDialog()
                Text(
                    when {
                        st.found != null -> "Lue sur l'autoradio : ${vs.outsideTempC?.let { "%.0f °C".format(it) } ?: "en attente d'une valeur"}."
                        else -> "Le moteur ne sert plus à l'estimer (il donnait 39 °C en novembre). " +
                            "Recopie la température que l'autoradio affiche en ce moment, contact mis : l'appli trouve où elle la lit."
                    },
                    fontSize = 13.sp, color = C.Sub,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { negative = !negative }) { Text(if (negative) "−" else "+", fontSize = 20.sp) }
                    Field(text, { text = it.filter(Char::isDigit).take(2) }, "°C affichés", Modifier.width(140.dp), number = true)
                    Button(enabled = text.isNotEmpty(), onClick = {
                        text.toIntOrNull()?.let { OutsideTemp.learn(if (negative) -it else it) }
                    }) { Text("Chercher") }
                }
                if (st.message.isNotEmpty()) Text(st.message, fontSize = 14.sp)
                else if (st.candidates > 1) Text("${st.candidates} canaux possibles : recommence quand la température aura changé.", fontSize = 14.sp)
                Text("Canaux reçus de l'autoradio : ${OutsideTemp.channelCount()}", fontSize = 12.sp, color = C.Sub)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = {
            if (st.found != null) TextButton(onClick = { OutsideTemp.forget() }) { Text("Oublier", color = C.Red) }
        },
    )
}
