package it.spese.feature.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.SubTopBar
import it.spese.core.common.Money
import it.spese.core.designsystem.component.*
import it.spese.domain.capture.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/** Cattura automatica dei movimenti dalle notifiche e dagli SMS delle sole app scelte. Tutto resta sul telefono. */
@Composable
fun CaptureScreen(onBack: () -> Unit, vm: CaptureViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val cfg by vm.config.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val seen by vm.seen.collectAsStateWithLifecycle()
    val until by vm.learningUntil.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var listenerOn by remember { mutableStateOf(false) }
    var postOk by remember { mutableStateOf(true) }
    fun refresh() {
        listenerOn = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        postOk = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(Unit) { refresh() }
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) { while (System.currentTimeMillis() < until) { now = System.currentTimeMillis(); delay(1000) }; now = System.currentTimeMillis() }
    val learning = now < until
    var ignoring by remember { mutableStateOf<UnrecognizedNotification?>(null) }
    ignoring?.let { u -> IgnoreDialog(u, vm, { ignoring = null }) }

    Scaffold(topBar = { SubTopBar("Movimenti da notifiche", onBack) }, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Come funziona") {
                    Text("Quando la tua banca o Google Wallet ti notificano un pagamento, l'app lo aggiunge da sola come movimento provvisorio e lo classifica con le tue regole. Quando poi importi l'estratto conto, la riga della banca lo sostituisce (categoria e note che hai scelto restano).", style = MaterialTheme.typography.bodyMedium)
                    Text("Privacy: l'app legge solo le notifiche delle app che attivi qui sotto, e per gli SMS solo dei mittenti che indichi. Tutte le altre sono scartate senza leggerle. Nessun dato lascia il telefono: l'app non ha accesso alla rete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = "Accesso alle notifiche") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (listenerOn) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = if (listenerOn) it.spese.core.designsystem.theme.SpeseTheme.finance.positive else MaterialTheme.colorScheme.error)
                        Text(if (listenerOn) "Concesso" else "Non ancora concesso", fontWeight = FontWeight.SemiBold)
                    }
                    Button(onClick = { openListenerSettings(ctx) }) { Text(if (listenerOn) "Gestisci in Android" else "Concedi l'accesso") }
                }
            }
            if (!listenerOn) item {
                SectionCard(title = "Interruttore grigio? Sblocca l'impostazione") {
                    Text("Android blocca l'accesso alle notifiche alle app installate da file, come misura di sicurezza: l'interruttore resta grigio e non si può attivare. Va sbloccato una volta sola, a mano:", style = MaterialTheme.typography.bodyMedium)
                    Text("1. Tocca «Apri info app».\n2. In alto a destra tocca i tre puntini ⋮ e scegli «Consenti impostazioni con restrizioni» (conferma con PIN o impronta).\n3. Torna qui e tocca «Concedi l'accesso»: ora l'interruttore si attiva.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { openAppInfo(ctx) }) { Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text("Apri info app") }
                    Text("Se i tre puntini non compaiono: prima tocca una volta l'interruttore grigio (Android mostra un avviso), poi riapri «Info app» e la voce c'è.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(CaptureSource.entries.toList(), key = { it.id }) { s -> SourceCard(s, cfg.of(s), data, vm, learning, now, until, seen) }
            item {
                SectionCard(title = "Avvisi") {
                    LabeledSwitch("Avvisami quando aggiungo un movimento", cfg.notifyOnAdd, vm::setNotify, supporting = "Con il pulsante Annulla, se ha letto male")
                    if (cfg.notifyOnAdd && !postOk && Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { askPost.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Consenti gli avvisi di Spese") }
                }
            }
            item { TryParser() }
            if (cfg.needsReview) item {
                SectionCard(title = "Hai ${cfg.unrecognized.size} notifiche da controllare") {
                    Text("Si sono accumulate notifiche che l'app non sa leggere. Per ognuna scegli: «Ignora simili» se non riguarda soldi (promozioni, codici, avvisi), oppure copiala e provala in «Prova un testo» se è un pagamento.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                SectionCard(title = "Non riconosciute (${cfg.unrecognized.size})", action = { if (cfg.unrecognized.isNotEmpty()) TextButton(onClick = vm::clearUnrecognized) { Text("Svuota") } }) {
                    Text("Notifiche delle fonti attive da cui non si è riusciti a ricavare un importo e una direzione certi, raggruppate se uguali. Restano solo qui, sul telefono.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (cfg.unrecognized.isEmpty()) Text("Nessuna.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(IgnoreFilter.group(cfg.unrecognized), key = { "u-${it.key}" }) { g ->
                val u = g.sample
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${u.source} · ${TIME.format(Instant.ofEpochMilli(u.atMs).atZone(ZoneId.systemDefault()))}" + if (g.count > 1) " · ${g.count} notifiche uguali" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(u.text, style = MaterialTheme.typography.bodyMedium)
                        if (IgnoreFilter.hasAmount(u.text)) Text("Contiene un importo: non può essere ignorata in automatico (potrebbe essere un pagamento).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            if (!IgnoreFilter.hasAmount(u.text)) TextButton(onClick = { ignoring = u }) { Text("Ignora simili") }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(u.text)) }) { Text("Copia") }
                            TextButton(onClick = { vm.removeUnrecognized(g.items) }) { Text(if (g.count > 1) "Elimina tutte" else "Elimina") }
                        }
                    }
                }
            }
            if (cfg.ignoreRules.isNotEmpty()) {
                item { SectionCard(title = "Notifiche ignorate (${cfg.ignoreRules.size})") {
                    Text("Le notifiche che contengono queste frasi vengono scartate senza leggerle, tranne se riportano un importo in euro.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                items(cfg.ignoreRules, key = { "ig-${it.createdMs}" }) { r ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("“${r.phrase}”", style = MaterialTheme.typography.bodyMedium)
                                Text(CaptureSource.entries.firstOrNull { it.id == r.source }?.label ?: "Qualsiasi fonte", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(r.enabled, { vm.toggleIgnoreRule(r.createdMs, it) })
                            IconButton(onClick = { vm.removeIgnoreRule(r.createdMs) }) { Icon(Icons.Default.Delete, "Elimina la regola") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(s: CaptureSource, c: SourceConfig, data: it.spese.domain.repository.AppData, vm: CaptureViewModel, learning: Boolean, now: Long, until: Long, seen: List<it.spese.app.capture.CaptureLearning.Seen>) {
    SectionCard(title = s.label) {
        LabeledSwitch("Leggi ${if (s.kind == SourceKind.SMS) "gli SMS" else "le notifiche"} di ${s.label}", c.enabled, { on -> vm.updateSource(s) { it.copy(enabled = on) } },
            supporting = when (s.kind) { SourceKind.WALLET -> "Notifica i pagamenti contactless; non dice da quale conto"; SourceKind.SMS -> "Solo dai mittenti indicati sotto"; SourceKind.BANK_APP -> null })
        if (c.enabled) {
            val acc = data.accounts.filter { !it.archived }
            DropdownField("Registra sul conto", acc.firstOrNull { it.id == c.accountId } ?: acc.firstOrNull(), acc, { it.name }, { a -> vm.updateSource(s) { it.copy(accountId = a.id) } })
            if (s.kind == SourceKind.SMS) {
                var text by remember(c.smsSenders) { mutableStateOf(c.smsSenders.joinToString(", ")) }
                OutlinedTextField(text, { text = it; vm.updateSource(s) { c0 -> c0.copy(smsSenders = it.split(',').map { x -> x.trim() }.filter { x -> x.isNotEmpty() }.toSet()) } },
                    label = { Text("Mittenti (separati da virgola)") }, placeholder = { Text("es. Postepay, HYPE") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Come compare il nome del mittente nella notifica dell'SMS. Gli altri SMS non vengono letti.") })
            }
            if (c.extraPackages.isNotEmpty()) c.extraPackages.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.updateSource(s) { it.copy(extraPackages = it.extraPackages - p) } }) { Text("Rimuovi") }
                }
            }
        }
        if (s == CaptureSource.POSTE || s == CaptureSource.HYPE) {
            if (!learning) OutlinedButton(onClick = vm::startLearning) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Rileva l'app") }
            else {
                Text("Fai arrivare ora una notifica dell'app di ${s.label} (o aprila): compare qui sotto. Rimangono ${((until - now) / 1000).coerceAtLeast(0)} s. Si ricorda solo il nome delle app, non il contenuto.", style = MaterialTheme.typography.bodySmall)
                seen.forEach { app ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(app.label, style = MaterialTheme.typography.titleSmall); Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { vm.useAsSource(s, app.packageName); vm.stopLearning() }) { Text("Usa per ${s.label}") }
                    }
                }
                TextButton(onClick = vm::stopLearning) { Text("Annulla") }
            }
        }
    }
}

@Composable
private fun TryParser() {
    var source by remember { mutableStateOf(CaptureSource.REVOLUT) }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    SectionCard(title = "Prova un testo") {
        Text("Incolla titolo e testo di una notifica per vedere cosa ne ricava l'app, senza aggiungere nulla.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipChoice(CaptureSource.entries.map { it to it.label }, source, { source = it })
        OutlinedTextField(title, { title = it }, label = { Text("Titolo (mittente per gli SMS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(text, { text = it }, label = { Text("Testo") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        if (title.isNotBlank() || text.isNotBlank()) {
            val r = NotificationParser.parse(source, title, text)
            Text(if (r == null) "Non riconosciuta: manca un importo in euro o una direzione certa (pagamento o accredito)." else "Riconosciuta: ${Money.eurSigned(r.amount)} · ${r.description}", style = MaterialTheme.typography.titleSmall,
                color = if (r == null) MaterialTheme.colorScheme.error else it.spese.core.designsystem.theme.SpeseTheme.finance.positive)
        }
    }
}

/** Apre direttamente la schermata dell'accesso alle notifiche di questa app (Android 11+), altrimenti l'elenco. */
private fun openListenerSettings(ctx: android.content.Context) {
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 30) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, android.content.ComponentName(ctx, it.spese.app.capture.CaptureListenerService::class.java).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(detail) }.isSuccess) return
    }
    runCatching { ctx.startActivity(list) }
}

/** Info dell'app: da qui, con ⋮, si consentono le "impostazioni con restrizioni". */
private fun openAppInfo(ctx: android.content.Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Conferma di una regola "ignora simili": la frase proposta si può correggere e si vede quante notifiche in elenco scarterebbe. */
@Composable
private fun IgnoreDialog(u: UnrecognizedNotification, vm: CaptureViewModel, onDismiss: () -> Unit) {
    var phrase by remember { mutableStateOf(IgnoreFilter.suggest(u.text)) }
    val rule = IgnoreRule(IgnoreFilter.sourceIdOf(u.source), IgnoreFilter.normalize(phrase), System.currentTimeMillis())
    val ok = rule.phrase.length >= IgnoreFilter.MIN_PHRASE
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ignora notifiche come questa") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Da ora scarterò in silenzio le notifiche di ${u.source} che contengono questa frase (anche con numeri diversi). Non vale mai per notifiche con un importo in euro.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(phrase, { phrase = it }, label = { Text("Frase da ignorare") }, supportingText = { Text(if (ok) "Toglierà ${vm.wouldRemove(rule)} notifiche dall'elenco." else "Scrivi almeno ${IgnoreFilter.MIN_PHRASE} lettere, per non ignorare troppo.") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { vm.addIgnoreRule(rule); onDismiss() }, enabled = ok) { Text("Ignora") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
