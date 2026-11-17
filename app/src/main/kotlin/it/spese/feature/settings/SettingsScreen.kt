package it.spese.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.Share
import it.spese.app.common.SubTopBar
import it.spese.app.common.stamp
import it.spese.core.designsystem.component.*
import it.spese.domain.repository.AppSettings
import it.spese.domain.repository.ThemeMode
import kotlinx.coroutines.launch

private val ANY_MIME = arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain", "*/*")

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDocs by remember { mutableStateOf(false) }
    var autoList by remember { mutableStateOf(false) }

    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::saveBackupJson) }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::saveFullBackup) }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::saveCsv) }
    val createXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { it?.let(vm::saveXlsx) }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::inspectUri) }
    val linkOpen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.link(u, false) } }
    val linkNew = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { u -> vm.link(u, true) } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> vm.update { it.copy(notificationsEnabled = ok || Build.VERSION.SDK_INT < 33) } }

    Scaffold(topBar = { SubTopBar("Backup e impostazioni", onBack) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                SectionCard(title = "Aspetto") {
                    ChipChoice(listOf(ThemeMode.SYSTEM to "Sistema", ThemeMode.LIGHT to "Chiaro", ThemeMode.DARK to "Scuro"), s.themeMode, { m -> vm.update { it.copy(themeMode = m) } })
                }
            }
            item {
                SectionCard(title = "Backup e ripristino") {
                    Text("${data.transactions.size} movimenti · il backup contiene movimenti, categorie, regole, budget, scadenze, obiettivi e scenari (formato compatibile con la versione web).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { createJson.launch("spese-backup-${stamp()}.json") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Salva backup (JSON)") }
                    OutlinedButton(onClick = { scope.launch { Share.shareBytes(ctx, "spese-backup-${stamp()}.json", "application/json", vm.backupJsonBytes(), "Condividi backup") } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Condividi backup…") }
                    OutlinedButton(onClick = { createZip.launch("spese-backup-completo-${stamp()}.zip") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderZip, null); Spacer(Modifier.width(8.dp)); Text("Backup completo con foto (ZIP)") }
                    OutlinedButton(onClick = { openBackup.launch(ANY_MIME) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Importa backup…") }
                }
            }
            item {
                SectionCard(title = "Backup automatico") {
                    LabeledSwitch("Backup automatico", s.autoBackupEnabled, { v -> vm.update { it.copy(autoBackupEnabled = v) } }, supporting = "Salva un file nella memoria privata dell'app (ultimi ${s.autoBackupKeep}). Per averne una copia fuori dal telefono usa \"Salva backup\" o la sincronizzazione.")
                    if (s.autoBackupEnabled) {
                        DropdownField("Frequenza", s.autoBackupEveryDays, listOf(1, 3, 7, 14, 30), { if (it == 1) "ogni giorno" else "ogni $it giorni" }, { d -> vm.update { it.copy(autoBackupEveryDays = d) } })
                        DropdownField("Copie conservate", s.autoBackupKeep, listOf(3, 5, 10, 20), { "$it" }, { k -> vm.update { it.copy(autoBackupKeep = k) } })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::autoBackupNow) { Text("Crea ora") }
                        OutlinedButton(onClick = { autoList = true }, enabled = storage.autoBackups > 0) { Text("Ripristina (${storage.autoBackups})") }
                    }
                    if (s.lastAutoBackupAt > 0) Text("Ultimo: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(s.lastAutoBackupAt)), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard(title = "Sincronizzazione con un file condiviso") {
                    Text("Scegli un file .json in una cartella sincronizzata (Google Drive, OneDrive, Dropbox…) dal selettore di Android. Su ogni dispositivo collega lo stesso file e tocca \"Sincronizza ora\": i dati vengono uniti (vince la modifica più recente) e il file viene aggiornato. Nessun server: l'app non ha accesso alla rete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.syncUri.isBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { linkNew.launch("spese-sync.json") }) { Text("Crea file") }
                        OutlinedButton(onClick = { linkOpen.launch(ANY_MIME) }) { Text("Collega esistente") }
                    } else {
                        Text("File collegato" + if (s.lastSyncAt > 0) " · ultima sincronizzazione " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(s.lastSyncAt)) else "")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = vm::syncNow) { Text("Sincronizza ora") }; OutlinedButton(onClick = vm::unlink) { Text("Scollega") } }
                    }
                }
            }
            item {
                SectionCard(title = "Esporta i movimenti") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { createCsv.launch("movimenti-${stamp()}.csv") }) { Text("CSV") }
                        OutlinedButton(onClick = { createXlsx.launch("movimenti-${stamp()}.xlsx") }) { Text("Excel") }
                    }
                    Text("Esporta tutti i ${data.transactions.size} movimenti. Per esportare solo quelli filtrati usa il menu nella scheda Movimenti.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = "Notifiche") {
                    LabeledSwitch("Notifiche locali", s.notificationsEnabled, { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 && !vm.notifier.canPost()) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.update { it.copy(notificationsEnabled = on) }
                    }, supporting = "Un riepilogo giornaliero generato sul telefono: nessun dato lascia il dispositivo")
                    if (s.notificationsEnabled) {
                        LabeledSwitch("Budget superati o in esaurimento", s.notifyBudgets, { v -> vm.update { it.copy(notifyBudgets = v) } })
                        LabeledSwitch("Scadenze imminenti o in ritardo", s.notifyDues, { v -> vm.update { it.copy(notifyDues = v) } })
                        LabeledSwitch("Addebiti ricorrenti attesi", s.notifyRecurring, { v -> vm.update { it.copy(notifyRecurring = v) } })
                        LabeledSwitch("Spese non previste", s.notifyUnexpected, { v -> vm.update { it.copy(notifyUnexpected = v) } })
                        LabeledSwitch("Quanto puoi spendere oggi", s.notifyDaily, { v -> vm.update { it.copy(notifyDaily = v) } }, supporting = "Ogni giorno, il tuo margine di spesa fino a fine mese")
                        LabeledSwitch("Riepilogo della settimana", s.notifyWeekly, { v -> vm.update { it.copy(notifyWeekly = v) } }, supporting = "Il lunedì: quanto hai speso negli ultimi 7 giorni")
                        DropdownField("Ora del controllo", s.notifyHour, (6..22).toList(), { "%02d:00".format(it) }, { h -> vm.update { it.copy(notifyHour = h) } })
                    }
                }
            }
            item { CaptureCard(s, vm) }
            item {
                SectionCard(title = "Documenti e spazio") {
                    Text("${storage.documents} foto · ${"%.1f".format(java.util.Locale.ITALY, storage.bytes / 1048576.0)} MB nella memoria privata dell'app. Non sono visibili ad altre app né inviate online.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { confirmDocs = true }, enabled = storage.documents > 0, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Elimina tutte le foto") }
                    Text("Le singole foto si eliminano dal dettaglio del movimento.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = "Dati") {
                    OutlinedButton(onClick = { confirmReset = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("Azzera tutti i dati") }
                    Text("Prima dell'azzeramento viene salvato uno snapshot: puoi annullare subito dal menu Dati.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = "Informazioni") {
                    Text("Gestione Spese · app nativa Android", fontWeight = FontWeight.Medium)
                    Text("Funziona interamente offline: l'app non richiede il permesso di accesso a Internet. Nessun account, nessun cloud.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    pending?.let { RestoreDialog(it, vm) }
    if (confirmReset) ConfirmDialog("Azzerare tutti i dati?", "Verranno cancellati movimenti, categorie personalizzate, regole, budget e scadenze, e ripristinate le categorie e regole predefinite. Potrai annullare subito dal menu Dati.", "Azzera", vm::resetAll, { confirmReset = false }, destructive = true)
    if (confirmDocs) ConfirmDialog("Eliminare tutte le foto?", "I movimenti restano, ma perdono il documento allegato.", "Elimina", vm::deleteAllDocuments, { confirmDocs = false }, destructive = true)
    if (autoList) AlertDialog(onDismissRequest = { autoList = false }, title = { Text("Backup automatici") }, confirmButton = {}, dismissButton = { TextButton(onClick = { autoList = false }) { Text("Chiudi") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { vm.autoBackups().forEach { f -> ListRow(f.name.removePrefix("auto-").removeSuffix(".json"), subtitle = "${f.length() / 1024} KB", onClick = { autoList = false; vm.inspectFile(f) }) } } })
}

private val CAPTURE_PRESETS = listOf(
    "Revolut" to "com.revolut.revolut", "N26" to "de.number26.android", "PayPal" to "com.paypal.android.p2pmobile", "Google Wallet" to "com.google.android.apps.walletnfcrel",
)

/** Registrazione automatica dei pagamenti dalle notifiche di banca/wallet: consenso esplicito, app scelte una per una, tutto sul telefono. */
@Composable
private fun CaptureCard(s: AppSettings, vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val seen by vm.seenApps.collectAsStateWithLifecycle()
    fun granted() = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    var listenerOn by remember { mutableStateOf(granted()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) listenerOn = granted() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val openAccess = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val pkgs = s.capturePackages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun setPkgs(l: List<String>) = vm.update { it.copy(capturePackages = l.distinct().joinToString(",")) }
    SectionCard(title = "Spese dalle notifiche") {
        Text("Se la banca o il wallet ti notifica ogni pagamento, l'app può registrarlo da sola e mantenere aggiornato il \"quanto posso spendere oggi\". " +
            "La lettura avviene solo su questo telefono e solo per le app che scegli qui sotto; nessun dato esce (l'app non ha accesso a Internet). " +
            "Quando importi l'estratto conto, i movimenti letti dalle notifiche vengono sostituiti da quelli della banca, senza duplicati.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledSwitch("Registra i pagamenti dalle notifiche", s.captureEnabled, { on -> vm.update { it.copy(captureEnabled = on) }; if (on && !listenerOn) openAccess() })
        if (s.captureEnabled) {
            if (!listenerOn) {
                Text("Manca il consenso di Android: abilita \"Gestione spese\" tra gli accessi alle notifiche.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Button(onClick = openAccess) { Text("Apri le impostazioni di Android") }
            }
            Text("App abilitate", style = MaterialTheme.typography.titleSmall)
            if (pkgs.isEmpty()) Text("Nessuna: scegline una qui sotto.", style = MaterialTheme.typography.bodySmall)
            pkgs.forEach { p -> ListRow(p, trailing = { TextButton(onClick = { setPkgs(pkgs - p) }) { Text("Togli") } }) }
            val suggestions = CAPTURE_PRESETS.filter { it.second !in pkgs }.map { it.first to it.second } + seen.filter { p -> p !in pkgs && CAPTURE_PRESETS.none { it.second == p } }.map { it to it }
            if (suggestions.isNotEmpty()) {
                Text("Aggiungi", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { (label, pkg) -> AssistChip(onClick = { setPkgs(pkgs + pkg) }, label = { Text(label) }) }
                }
                Text("Le app che mandano una notifica di pagamento compaiono qui da sole: fai un acquisto di prova e torna in questa schermata.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var acc by remember(s.captureAccount) { mutableStateOf(s.captureAccount) }
            OutlinedTextField(acc, { acc = it; vm.update { st -> st.copy(captureAccount = it) } }, label = { Text("Conto (vuoto = il primo)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RestoreDialog(p: RestorePrompt, vm: SettingsViewModel) {
    var confirmReplace by remember { mutableStateOf(false) }
    val env = p.envelope
    AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("Importa backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("File: ${p.fileName}", style = MaterialTheme.typography.bodySmall)
                Text("Creato ${env.savedAt.ifBlank { "(data non indicata)" }.take(16).replace('T', ' ')} da ${env.device}", style = MaterialTheme.typography.bodySmall)
                Text("Contenuto: ${env.state.tx.size} movimenti · ${env.state.tx.map { it.acc }.distinct().joinToString(", ")}", fontWeight = FontWeight.Medium)
                Text("Questo dispositivo: ${p.localTx} movimenti", style = MaterialTheme.typography.bodySmall)
                if (p.files.isNotEmpty()) Text("Include ${p.files.size} file di documenti.", style = MaterialTheme.typography.bodySmall)
                p.issues.forEach { Text((if (it.error) "⛔ " else "⚠ ") + it.message, style = MaterialTheme.typography.bodySmall, color = if (it.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) }
                HorizontalDivider()
                Text("Se scegli \"Unisci\"", style = MaterialTheme.typography.titleSmall)
                val l = p.plan.summary()
                if (l.isEmpty()) Text("Nessuna differenza: i dati sono già allineati.", style = MaterialTheme.typography.bodySmall) else l.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Unisci non cancella nulla: aggiunge i movimenti mancanti (i duplicati sono riconosciuti) e, per categorie, regole, assegnazioni, budget, scadenze e obiettivo, vince la modifica più recente. Prima di ogni ripristino viene salvato uno snapshot per annullare.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { vm.confirmRestore(false) }) { Text("Unisci") } },
        dismissButton = { Row { TextButton(onClick = { confirmReplace = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Sostituisci tutto") }; TextButton(onClick = vm::cancelRestore) { Text("Annulla") } } })
    if (confirmReplace) ConfirmDialog("Sostituire tutti i dati?", "I ${p.localTx} movimenti di questo dispositivo verranno sostituiti da quelli del backup (${env.state.tx.size}). Potrai annullare subito dal menu Dati.", "Sostituisci", { vm.confirmRestore(true) }, { confirmReplace = false }, destructive = true)
}
