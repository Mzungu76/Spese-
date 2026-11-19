package it.spese.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import it.spese.core.designsystem.component.LabeledSwitch
import it.spese.core.model.TxOrigin
import it.spese.domain.usecase.RestartPlan

/**
 * «Ricomincia dagli estratti»: dice con i numeri cosa si tiene e cosa si cancella, e chiede di scrivere RICOMINCIA.
 * Si tengono i dettagli degli scontrini (che la reimportazione abbina alle righe dell'estratto), i provvisori e, a scelta, i movimenti a mano.
 */
@Composable
fun RestartDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    var keepManual by remember { mutableStateOf(true) }
    var plan by remember { mutableStateOf<RestartPlan?>(null) }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(keepManual) { plan = vm.restartPreview(keepManual) }
    val p = plan
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ricominciare dagli estratti?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p == null) { Text("Controllo i movimenti…"); return@Column }
                Text("Si cancellano ${p.toDelete.size} movimenti: le righe importate da estratto conto o da backup che non hanno dettagli di scontrino.", style = MaterialTheme.typography.bodyMedium)
                Text("Si tengono:", style = MaterialTheme.typography.titleSmall)
                Text("• ${p.withDetails.size} con i dettagli di uno scontrino o una divisione scelta da te: tornano «in attesa dell'estratto» e la reimportazione li sostituisce con la riga giusta (stesso importo, data entro 5 giorni), conservando foto, voci, note e categoria", style = MaterialTheme.typography.bodySmall)
                Text("• ${p.provisional.size} provvisori da notifica o ricorrenza", style = MaterialTheme.typography.bodySmall)
                val manualCount = p.manual.size + p.toDelete.count { t -> t.origin == TxOrigin.MANUAL }
                LabeledSwitch("Tieni i movimenti inseriti a mano ($manualCount)", keepManual, { keepManual = it }, supporting = "Per esempio le spese in contanti: l'estratto non le contiene")
                Text("Restano conti, saldi iniziali, categorie, regole, budget e obiettivi. Si perdono le categorie e i giroconti che avevi assegnato a mano sulle righe importate: le regole li riassegnano alla reimportazione, e i giroconti li riconfermi da Altro › Giroconti probabili.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Prima di procedere salvo una copia: dal menu ⋮ › Annulla l'ultima operazione torni indietro.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(typed, { typed = it }, label = { Text("Per confermare scrivi: RICOMINCIA") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("restart_confirm_text"))
            }
        },
        confirmButton = {
            TextButton(enabled = p != null && typed.trim().equals("RICOMINCIA", ignoreCase = true), onClick = { vm.restartFromStatements(keepManual); onDismiss() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("restart_confirm")) { Text("Ricomincia") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
