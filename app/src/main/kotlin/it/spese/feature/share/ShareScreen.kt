package it.spese.feature.share

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.SubTopBar
import it.spese.app.common.UiMessenger
import it.spese.app.work.WorkScheduler
import it.spese.core.designsystem.component.*
import it.spese.domain.repository.AppData
import it.spese.domain.sharing.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** `link` nullo = ancora in lettura; `Loaded(null)` = nessuna condivisione. */
data class ShareUi(val link: ShareLink?)

@HiltViewModel
class ShareViewModel @Inject constructor(
    val data: StateFlow<AppData>, links: ShareLinkRepository, private val service: ShareService, private val messenger: UiMessenger, private val scheduler: WorkScheduler,
) : ViewModel() {
    val state: StateFlow<ShareUi?> = links.observe().map { l -> ShareUi(l) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private fun run(done: String?, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try { block(); if (done != null) messenger.show(done) }
        catch (e: ShareApiException) { messenger.show(e.message ?: "Operazione non riuscita") }
        catch (e: Exception) { messenger.show("Operazione non riuscita") }
        finally { _busy.value = false }
    }

    fun create(url: String, createKey: String, accountIds: List<Long>, captureAccountId: Long, cfg: ShareConfig) = run("Condivisione creata") { service.create(url, createKey, accountIds, captureAccountId, cfg); scheduler.setShareSync(true) }
    fun saveConfig(cfg: ShareConfig, accountIds: List<Long>, captureAccountId: Long) = run("Scelte salvate e pubblicate") { service.updateConfig(cfg, accountIds, captureAccountId) }
    private val _preview = MutableStateFlow<it.spese.domain.finance.DailyBudget?>(null)
    val preview: StateFlow<it.spese.domain.finance.DailyBudget?> = _preview
    fun refreshPreview() = viewModelScope.launch { _preview.value = runCatching { service.preview() }.getOrNull() }
    fun sendMessage(text: String) = run(null) { service.sendMessage(text) }
    fun sync() = run(null) {
        val r = service.sync()
        messenger.show(r.problem ?: if (r.imported > 0) "Importate ${r.imported} spese dall'altro telefono" else "Tutto aggiornato")
    }
    fun revoke() = run(null) {
        val ok = service.revoke(); scheduler.setShareSync(false)
        messenger.show(if (ok) "Condivisione revocata: l'altra persona non vede più nulla" else "Condivisione dimenticata sul telefono; il servizio non ha risposto, riprova a revocare da internet più tardi")
    }
}

private fun plain(s: String?) = s?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: ""
private val stamp = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private fun whenText(ms: Long) = if (ms <= 0) "mai" else stamp.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
fun ShareScreen(onBack: () -> Unit, vm: ShareViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val d by vm.data.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Scaffold(topBar = { SubTopBar("Condivisione", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val link = ui?.link
            when {
                ui == null -> Unit
                link == null -> CreateCard(d, busy) { url, key, ids, capture, cfg -> vm.create(url, key, ids, capture, cfg) }
                else -> ActiveCards(link, d, busy, vm)
            }
        }
    }
}

@Composable
private fun CreateCard(d: AppData, busy: Boolean, onCreate: (String, String, List<Long>, Long, ShareConfig) -> Unit) {
    var url by remember { mutableStateOf(ShareDefaults.SERVER_URL) }
    var key by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var captureId by remember { mutableStateOf<Long?>(null) }
    var budget by remember { mutableStateOf("") }
    var savings by remember { mutableStateOf("") }
    var capture by remember { mutableStateOf(true) }
    var chart by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    val accounts = d.accounts.filter { a -> !a.archived }
    SectionCard(title = "Condividi un conto con un'altra persona") {
        Text("L'altra persona vede solo il budget di spesa quotidiano e il grafico del ritmo del conto che scegli. I dati sono cifrati sul telefono: il servizio online non vede importi né descrizioni.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, { v -> url = v }, label = { Text("Indirizzo del servizio") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("share_url"))
        OutlinedTextField(key, { v -> key = v }, label = { Text("Chiave di creazione (impostata su Cloudflare)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("share_key"))
        if (accounts.isEmpty()) Text("Crea prima un conto in Altro › Conti.", style = MaterialTheme.typography.bodySmall)
        else AccountPicker(accounts, picked, captureId, { set -> picked = set }, { id -> captureId = id })
        AmountField(budget, { v -> budget = v }, "Budget mensile (vuoto = dalle entrate recenti del conto)", Modifier.fillMaxWidth())
        AmountField(savings, { v -> savings = v }, "Da mettere da parte ogni mese (vuoto = dagli obiettivi)", Modifier.fillMaxWidth())
        LabeledSwitch("Rileva le spese dalle notifiche", capture, { v -> capture = v }, supporting = "L'app dell'altra persona le manda a te come movimenti provvisori del conto")
        LabeledSwitch("Mostra il grafico del ritmo", chart, { v -> chart = v }, supporting = "Se spento vede solo la cifra giornaliera")
        OutlinedTextField(note, { v -> note = v }, label = { Text("Messaggio per l'altra persona (facoltativo)") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && picked.isNotEmpty() && key.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("share_create"), onClick = {
            val ids = accounts.map { a -> a.id }.filter { id -> id in picked }
            onCreate(url, key, ids, captureId?.takeIf { id -> id in ids } ?: ids.first(), ShareConfig(parseAmountOrNull(budget), parseAmountOrNull(savings), ShareFeatures(capture, chart), note.trim()))
        }) { Text("Crea la condivisione") }
    }
}

@Composable
private fun ActiveCards(link: ShareLink, d: AppData, busy: Boolean, vm: ShareViewModel) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val code = remember(link.shareId, link.keyText, link.guestToken) { SharePairing.encode(link) }
    var confirmRevoke by remember { mutableStateOf(false) }
    SectionCard(title = "Condivisione attiva") {
        Text((if (link.sharedIds.size > 1) "Conti: " else "Conto: ") + link.sharedIds.joinToString(", ") { id -> d.accountName(id) }, style = MaterialTheme.typography.titleMedium)
        Text("Ultimo riepilogo pubblicato: " + whenText(link.lastPublishedAt) + " · ultimo controllo spese: " + whenText(link.lastPulledAt), style = MaterialTheme.typography.bodyMedium)
        Text("Spese importate dall'altro telefono: ${link.importedCount}", style = MaterialTheme.typography.bodyMedium)
        Button(enabled = !busy, onClick = { vm.sync() }, modifier = Modifier.fillMaxWidth().testTag("share_sync")) { Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Sincronizza ora") }
        Text("In automatico l'app controlla circa ogni quarto d'ora, quando il telefono ha rete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard(title = "Codice per l'altra persona") {
        Text("Mandale questo codice (non condividerlo in pubblico): lo incolla nell'app Condivisione sul suo telefono. Contiene il suo accesso e la chiave di cifratura, non il tuo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(code, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("share_code")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copia") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, code) }
                ctx.startActivity(Intent.createChooser(send, "Invia il codice"))
            }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Invia") }
        }
    }
    val preview by vm.preview.collectAsStateWithLifecycle()
    LaunchedEffect(link, d.transactions.size, d.loaded) { vm.refreshPreview() }
    BudgetCalcCard(preview, link)
    ThreadCard(link, busy) { t -> vm.sendMessage(t) }
    ConfigCard(link, d, busy) { cfg, ids, capture -> vm.saveConfig(cfg, ids, capture) }
    OutlinedButton(onClick = { confirmRevoke = true }, enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth().testTag("share_revoke")) { Text("Revoca la condivisione") }
    if (confirmRevoke) AlertDialog(onDismissRequest = { confirmRevoke = false }, title = { Text("Revocare la condivisione?") },
        text = { Text("L'altra persona non vedrà più nulla e le spese non ancora importate sul servizio vengono cancellate. I movimenti già importati restano nell'app.") },
        confirmButton = { TextButton(onClick = { confirmRevoke = false; vm.revoke() }) { Text("Revoca") } },
        dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Annulla") } })
}

@Composable
private fun ConfigCard(link: ShareLink, d: AppData, busy: Boolean, onSave: (ShareConfig, List<Long>, Long) -> Unit) {
    var budget by remember(link.budgetMonthly) { mutableStateOf(plain(link.budgetMonthly)) }
    var savings by remember(link.savingsMonthly) { mutableStateOf(plain(link.savingsMonthly)) }
    var capture by remember(link.captureNotifications) { mutableStateOf(link.captureNotifications) }
    var chart by remember(link.showChart) { mutableStateOf(link.showChart) }
    var note by remember(link.note) { mutableStateOf(link.note) }
    var picked by remember(link.sharedIds) { mutableStateOf(link.sharedIds.toSet()) }
    var captureId by remember(link.accountId) { mutableStateOf<Long?>(link.accountId) }
    val accounts = d.accounts.filter { a -> !a.archived || a.id in link.sharedIds }
    SectionCard(title = "Cosa vede l'altra persona") {
        AccountPicker(accounts, picked, captureId, { set -> picked = set }, { id -> captureId = id })
        AmountField(budget, { v -> budget = v }, "Budget mensile (vuoto = dalle entrate recenti)", Modifier.fillMaxWidth())
        AmountField(savings, { v -> savings = v }, "Da mettere da parte ogni mese (vuoto = dagli obiettivi)", Modifier.fillMaxWidth())
        LabeledSwitch("Rileva le spese dalle notifiche", capture, { v -> capture = v })
        LabeledSwitch("Mostra il grafico del ritmo", chart, { v -> chart = v })
        OutlinedTextField(note, { v -> note = v }, label = { Text("Messaggio per l'altra persona") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && picked.isNotEmpty(), onClick = {
            val ids = accounts.map { a -> a.id }.filter { id -> id in picked }
            onSave(ShareConfig(parseAmountOrNull(budget), parseAmountOrNull(savings), ShareFeatures(capture, chart), note.trim()), ids, captureId?.takeIf { id -> id in ids } ?: ids.first())
        }, modifier = Modifier.fillMaxWidth().testTag("share_save")) { Text("Salva e aggiorna") }
    }
}

/** Scelta di uno o più conti da condividere e, se sono più d'uno, di quello che riceve le spese rilevate dall'altro telefono. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountPicker(accounts: List<it.spese.core.model.Account>, picked: Set<Long>, captureId: Long?, onPicked: (Set<Long>) -> Unit, onCapture: (Long) -> Unit) {
    Text("Conti da condividere", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a ->
            FilterChip(a.id in picked, { onPicked(if (a.id in picked) picked - a.id else picked + a.id) }, { Text(a.name) }, Modifier.testTag("share_acc_${a.id}"))
        }
    }
    Text("Budget e ritmo sono quelli di tutti i conti scelti insieme.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val chosen = accounts.filter { a -> a.id in picked }
    if (chosen.size > 1) {
        Text("Le spese rilevate dall'altro telefono vanno su", style = MaterialTheme.typography.labelLarge)
        ChipChoice(chosen.map { a -> a.id to a.name }, captureId?.takeIf { id -> id in picked } ?: chosen.first().id, { id -> onCapture(id) })
    }
}

/** La conversazione con l'altra persona: lei scrive dall'app e tu rispondi da qui; i messaggi sono cifrati come il resto. */
@Composable
private fun ThreadCard(link: ShareLink, busy: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    SectionCard(title = "Messaggi") {
        if (link.thread.isEmpty()) Text("Nessun messaggio.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        link.thread.takeLast(12).forEach { m ->
            Text((if (m.fromHost) "Tu: " else "Lei/lui: ") + m.text, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(text, { v -> text = v }, label = { Text("Scrivi") }, modifier = Modifier.fillMaxWidth().testTag("share_msg"))
        Button(enabled = !busy && text.isNotBlank(), onClick = { onSend(text); text = "" }, modifier = Modifier.fillMaxWidth().testTag("share_msg_send")) { Text("Invia") }
    }
}

/** Come si arriva alla cifra al giorno, con i numeri dei conti condivisi: serve a controllare che i conti tornino. */
@Composable
private fun BudgetCalcCard(b: it.spese.domain.finance.DailyBudget?, link: ShareLink) {
    SectionCard(title = "Calcolo del budget") {
        if (b == null) { Text("Non ci sono ancora movimenti sui conti condivisi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); return@SectionCard }
        fun line(label: String, v: BigDecimal?, sign: String = "") { if (v != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodyMedium); Text(sign + it.spese.core.common.Money.eur0(v), style = MaterialTheme.typography.bodyMedium) } }
        line("Entrate attese", b.expectedIncome)
        line("Risparmio (obiettivi)", b.savings, "− ")
        line("Margine di sicurezza", b.reserve, "− ")
        line("Limite del mese", b.limit)
        line("Speso finora", b.spent, "− ")
        line("Uscite fisse ancora da pagare", b.upcomingTotal, "− ")
        line("Resta", b.remaining)
        Text("${b.daysLeft} giorni (oggi compreso) · al giorno " + (b.perDay?.let { v -> it.spese.core.common.Money.eur(v) } ?: "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val est = b.income
        if (b.expectedIncome.signum() == 0 && link.budgetMonthly == null)
            Text("I conti condivisi non hanno entrate (i giroconti non contano come entrate): per questo il limite è zero. Fissa un budget mensile qui sotto.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        else if (est != null) Text("Entrate stimate su ${est.months} mesi: fisse ${it.spese.core.common.Money.eur0(est.fixed)}, variabili prudenti ${it.spese.core.common.Money.eur0(est.variablePrudent)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Solo movimenti, ricorrenze e abbonamenti dei conti condivisi; le scadenze non legate a un conto restano fuori. Il risparmio è quello dei tuoi obiettivi (scrivi 0 nel campo risparmio per non toglierlo).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
