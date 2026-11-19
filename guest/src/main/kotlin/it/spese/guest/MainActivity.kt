package it.spese.guest

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.chart.ChartSeries
import it.spese.core.designsystem.chart.LineChart
import it.spese.core.designsystem.component.SectionCard
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.capture.CaptureSource
import it.spese.domain.sharing.GuestState
import it.spese.domain.sharing.ShareApiException
import it.spese.domain.sharing.ShareSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GuestApp
        // sempre scuro, nero pieno: si legge bene di giorno e di sera e risparmia batteria sugli schermi OLED
        setContent { SpeseTheme(darkTheme = true) { Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) { GuestRoot(app) } } }
    }
}

/** `state` nullo = ancora in lettura; `Holder(null)` = nessun abbinamento. */
private data class Holder(val state: GuestState?)

@Composable
private fun GuestRoot(app: GuestApp) {
    val flow = remember { app.store.observe() }
    val holder by produceState<Holder?>(null, flow) { flow.collect { st -> value = Holder(st) } }
    val ctx = LocalContextCompat()
    // a ogni apertura si aggiorna il riepilogo e si consegnano le spese in coda
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (holder?.state != null) GuestScheduler.syncSoon(ctx) }
    LaunchedEffect(holder?.state != null) { if (holder?.state != null) GuestScheduler.ensurePeriodic(ctx) }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val st = holder?.state
        when {
            holder == null -> Unit
            st == null -> PairScreen(app)
            else -> HomeScreen(app, st)
        }
    }
}

@Composable
private fun LocalContextCompat(): Context = androidx.compose.ui.platform.LocalContext.current

@Composable
private fun PairScreen(app: GuestApp) {
    val ctx = LocalContextCompat()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("Condivisione Spese", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    SectionCard(title = "Collega il conto condiviso") {
        Text("Chi ti ha condiviso il conto ti ha mandato un codice che inizia con SPESE1. Incollalo qui: vedrai quanto puoi spendere ogni giorno.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(code, { v -> code = v; error = null }, label = { Text("Codice di abbinamento") }, modifier = Modifier.fillMaxWidth(), minLines = 3, isError = error != null)
        error?.let { e -> Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                code = cm.primaryClip?.takeIf { c -> c.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            }) { Text("Incolla") }
            Button(enabled = code.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try { app.service.pair(code); GuestScheduler.ensurePeriodic(ctx) }
                    catch (e: ShareApiException) { error = e.message }
                    finally { busy = false }
                }
            }) { Text("Collega") }
        }
    }
}

private val hour = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private fun stamp(ms: Long) = if (ms <= 0) "mai" else hour.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun HomeScreen(app: GuestApp, st: GuestState) {
    val ctx = LocalContextCompat()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val snap = st.snapshot
    Text(snap?.title ?: "Condivisione Spese", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    PeriodFilter(st) { u -> scope.launch { app.service.setUnit(u) } }
    if (snap == null) {
        SectionCard(title = "In attesa del riepilogo") {
            Text("Chi ha condiviso il conto non ha ancora pubblicato il budget (servono alcuni movimenti, oppure un budget fissato a mano). Riprova tra poco.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        BudgetCard(snap)
        if (snap.features.showChart) ChartCard(snap)
        if (snap.note.isNotBlank()) SectionCard(title = "Messaggio") { Text(snap.note, style = MaterialTheme.typography.bodyMedium) }
    }
    if (snap?.features?.captureNotifications != false) CaptureCard(app, st)
    SectionCard {
        Text("Riepilogo aggiornato: " + stamp(st.snapshotUpdated) + " · ultimo controllo: " + stamp(st.lastRefreshAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (st.problem.isNotBlank()) Text(st.problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { busy = true; scope.launch { app.service.sync(); busy = false } }) { Text(if (busy) "Aggiorno…" else "Aggiorna ora") }
            OutlinedButton(onClick = { confirmUnpair = true }) { Text("Scollega") }
        }
    }
    if (confirmUnpair) AlertDialog(onDismissRequest = { confirmUnpair = false }, title = { Text("Scollegare il conto?") },
        text = { Text("Non vedrai più il budget e le spese non ancora consegnate (${st.outbox.size}) andranno perse. Potrai ricollegarti con lo stesso codice.") },
        confirmButton = { TextButton(onClick = { confirmUnpair = false; scope.launch { app.service.unpair(); GuestScheduler.cancel(ctx) } }) { Text("Scollega") } },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Annulla") } })
}

private val PERIODS = listOf("WEEK" to "Settimana", "MONTH" to "Mese", "QUARTER" to "Trimestre")

/** I periodi che la madre ha pubblicato (almeno due, altrimenti non serve scegliere). */
@Composable
private fun PeriodFilter(st: GuestState, onPick: (String) -> Unit) {
    val available = st.snapshots.map { x -> x.unit }.toSet()
    val options = PERIODS.filter { (u, _) -> u in available }
    if (options.size < 2) return
    val current = st.snapshot?.unit
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (u, label) ->
            SegmentedButton(selected = u == current, onClick = { onPick(u) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
        }
    }
}

@Composable
private fun BudgetCard(s: ShareSnapshot) {
    val fin = SpeseTheme.finance
    val perDay = s.dailyRemaining
    SectionCard(title = when (s.unit) { "WEEK" -> "Questa settimana"; "QUARTER" -> "Questo trimestre"; else -> "Questo mese" }) {
        Text("Oggi puoi spendere", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(perDay?.let { v -> Money.eur(v.max(java.math.BigDecimal.ZERO)) } ?: "—", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
            color = if (perDay != null && perDay.signum() <= 0) fin.negative else fin.positive)
        if (perDay == null) Text(if (s.limit == null) "Il limite non è ancora calcolabile." else "Ultimo giorno del periodo: non resta una cifra al giorno.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (perDay != null && perDay.signum() <= 0) Text("Il limite del periodo è già stato raggiunto.", style = MaterialTheme.typography.bodyMedium, color = fin.negative)
        s.limit?.takeIf { l -> l.signum() > 0 }?.let { l ->
            val used = (s.spentToDate.toFloat() / l.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { used }, Modifier.fillMaxWidth().height(10.dp), color = if (s.spentToDate > l) fin.negative else fin.positive, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
        s.limit?.let { l -> Text("Limite del periodo: " + Money.eur0(l) + " · speso finora: " + Money.eur0(s.spentToDate), style = MaterialTheme.typography.bodyMedium) }
        s.optimalToDate?.let { o -> Text("A questo punto sarebbe ottimale aver speso " + Money.eur0(o) + (if (s.spentToDate <= o) " — sei in linea." else " — sei sopra."), style = MaterialTheme.typography.bodyMedium) }
        s.dailyAverage?.let { a -> Text("Media giornaliera ideale: " + Money.eur(a), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Dal ${Dates.fmt(s.periodStart)} al ${Dates.fmt(s.periodEnd)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartCard(s: ShareSnapshot) {
    SectionCard(title = "Ritmo di spesa") {
        val labels = (0 until s.days).map { i -> if (s.unit == "WEEK") listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")[i % 7] else "${i + 1}" }.let { l -> if (s.unit == "QUARTER") l.mapIndexed { i, x -> if (i % 15 == 0) x else "" } else l }
        val series = listOf(
            ChartSeries("Media dei periodi passati", MaterialTheme.colorScheme.onSurfaceVariant, s.average),
            ChartSeries("Spesa ottimale", MaterialTheme.colorScheme.primary, s.optimal),
            ChartSeries("Speso finora", SpeseTheme.finance.negative, s.current),
        ).filter { x -> x.values.size >= 2 }
        if (series.isEmpty()) Text("Ancora nessuna spesa da confrontare.", style = MaterialTheme.typography.bodyMedium)
        else LineChart(labels, series, interactive = false)
        Text("La linea blu è la spesa ottimale: il limite per restare in linea; quella rossa è ciò che hai speso finora.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureCard(app: GuestApp, st: GuestState) {
    val ctx = LocalContextCompat()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val chosen = st.activeSources.map { s -> s.id }.toSet()
    SectionCard(title = "Spese dalle notifiche") {
        Text("Quando paghi, la notifica della banca viene letta sul tuo telefono e la spesa arriva, cifrata, a chi ha condiviso il conto. Nessun'altra notifica viene letta.", style = MaterialTheme.typography.bodyMedium)
        if (!enabled) {
            Text("Per attivarlo serve consentire l'accesso alle notifiche.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Consenti l'accesso") }
        } else Text("Accesso alle notifiche attivo.", style = MaterialTheme.typography.bodyMedium)
        Text("App da ascoltare", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureSource.entries.filter { s -> s != CaptureSource.SMS }.forEach { s ->
                FilterChip(s.id in chosen, { scope.launch { app.service.setSources(if (s.id in chosen) chosen.filter { id -> id != s.id } else chosen.toList() + s.id) } }, { Text(s.label) })
            }
        }
        Text("In coda da consegnare: ${st.outbox.size} · consegnate: ${st.sentCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
