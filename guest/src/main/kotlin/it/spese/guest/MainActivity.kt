package it.spese.guest

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.designsystem.chart.ChartSeries
import it.spese.core.designsystem.chart.LineChart
import it.spese.core.designsystem.component.SectionCard
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.capture.CaptureSource
import it.spese.domain.sharing.GuestState
import it.spese.domain.sharing.GuestSummary
import it.spese.domain.sharing.ShareApiException
import it.spese.domain.sharing.ShareSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GuestApp
        // sempre scuro, nero pieno: si legge bene di giorno e di sera e risparmia batteria sugli schermi OLED
        setContent { SpeseTheme(darkTheme = true) { Surface(Modifier.fillMaxSize(), color = Color.Black) { GuestRoot(app) } } }
    }
}

/** `state` nullo = ancora in lettura; `Holder(null)` = nessun abbinamento. */
private data class Holder(val state: GuestState?)

@Composable
private fun GuestRoot(app: GuestApp) {
    val flow = remember { app.store.observe() }
    val holder by produceState<Holder?>(null, flow) { flow.collect { st -> value = Holder(st) } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
private fun PairScreen(app: GuestApp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("Collega", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    SectionCard {
        OutlinedTextField(code, { v -> code = v; error = null }, label = { Text("Codice SPESE1…") }, modifier = Modifier.fillMaxWidth(), minLines = 3, isError = error != null)
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
private val dayFmt = DateTimeFormatter.ofPattern("dd/MM")
private fun stamp(ms: Long) = if (ms <= 0) "mai" else hour.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun dayLabel(d: LocalDate, today: LocalDate) = when (d) { today -> "Oggi"; today.minusDays(1) -> "Ieri"; else -> dayFmt.format(d) }

@Composable
private fun HomeScreen(app: GuestApp, st: GuestState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val snap = st.snapshot
    PeriodFilter(st) { u -> scope.launch { app.service.setUnit(u) } }
    if (snap == null) {
        SectionCard { Text("In attesa dei dati.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        BudgetCard(snap)
        if (snap.features.showChart) ChartCard(snap)
        DetailCard(snap)
        if (snap.recent.isNotEmpty()) RecentCard(snap)
        if (snap.categories.isNotEmpty()) CategoriesCard(snap)
    }
    MessagesCard(app, st)
    if (snap?.features?.captureNotifications != false) CaptureCard(app, st)
    AlertsCard(app, st)
    SectionCard {
        Text("Dati del " + stamp(st.snapshotUpdated) + " · controllo " + stamp(st.lastRefreshAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (st.problem.isNotBlank()) Text(st.problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { busy = true; scope.launch { app.service.sync(); busy = false } }) { Text(if (busy) "Aggiorno…" else "Aggiorna") }
            OutlinedButton(onClick = { confirmUnpair = true }) { Text("Scollega") }
        }
    }
    if (confirmUnpair) AlertDialog(onDismissRequest = { confirmUnpair = false }, title = { Text("Scollegare?") },
        text = { Text("Le spese e i messaggi non ancora consegnati (${st.outbox.size + st.pendingMsgs.size}) andranno persi. Potrai ricollegarti con lo stesso codice.") },
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

/** Solo i numeri: la cifra al giorno, quanto è stato speso e il limite. Nessun commento. */
@Composable
private fun BudgetCard(s: ShareSnapshot) {
    val fin = SpeseTheme.finance
    val perDay = s.dailyRemaining
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(GuestSummary.perDay(s), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = if (perDay != null && perDay.signum() <= 0) fin.negative else fin.positive)
            Text("al giorno", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (s.delayDays != null && s.delayUntil != null) Text("Con un ritardo di ${s.delayDays} giorni sulle entrate fisse: i soldi coprono fino al ${Dates.fmt(s.delayUntil!!).take(5)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.limit?.takeIf { l -> l.signum() > 0 }?.let { l ->
            val used = (s.spentToDate.toFloat() / l.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { used }, Modifier.fillMaxWidth().height(10.dp), color = if (s.spentToDate > l) fin.negative else fin.positive, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Speso", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(Money.eur0(s.spentToDate), fontWeight = FontWeight.SemiBold)
        }
        s.upcomingTotal?.takeIf { u -> u.signum() > 0 }?.let { u -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Da pagare", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(Money.eur0(u), fontWeight = FontWeight.SemiBold) } }
        s.limit?.let { l -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Limite", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(Money.eur0(l), fontWeight = FontWeight.SemiBold) } }
        s.optimalToDate?.let { o -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Previsto a oggi", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(Money.eur0(o), fontWeight = FontWeight.SemiBold) } }
        Text("${Dates.fmt(s.periodStart)} – ${Dates.fmt(s.periodEnd)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartCard(s: ShareSnapshot) {
    SectionCard {
        val labels = (0 until s.days).map { i -> if (s.unit == "WEEK") listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")[i % 7] else "${i + 1}" }.let { l -> if (s.unit == "QUARTER") l.mapIndexed { i, x -> if (i % 15 == 0) x else "" } else l }
        val series = listOf(
            ChartSeries("Media", MaterialTheme.colorScheme.onSurfaceVariant, s.average),
            ChartSeries("Ottimale", MaterialTheme.colorScheme.primary, s.optimal),
            ChartSeries("Speso", SpeseTheme.finance.negative, s.current),
        ).filter { x -> x.values.size >= 2 }
        if (series.isEmpty()) Text("Nessuna spesa.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LineChart(labels, series, interactive = false)
    }
}

@Composable
private fun RecentCard(s: ShareSnapshot) {
    val today = LocalDate.now()
    SectionCard(title = "Ultime spese") {
        s.recent.take(8).forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.description, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(dayLabel(r.day, today) + if (r.account.isNotBlank()) " · " + r.account else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Money.eur(r.amount), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CategoriesCard(s: ShareSnapshot) {
    SectionCard(title = "Categorie") {
        s.categories.forEach { c ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(c.name); Text("${c.percent}%", fontWeight = FontWeight.SemiBold) }
            LinearProgressIndicator(progress = { c.percent / 100f }, Modifier.fillMaxWidth().height(7.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
    }
}

/** La conversazione con chi ha condiviso il conto: i messaggi si vedono subito e partono appena c'è rete. */
@Composable
private fun MessagesCard(app: GuestApp, st: GuestState) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val thread = st.conversation
    val note = st.snapshot?.note.orEmpty()
    SectionCard(title = "Messaggi") {
        if (note.isNotBlank()) Bubble(note, mine = false)
        thread.takeLast(10).forEach { m -> Bubble(m.text, mine = !m.fromHost) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { v -> text = v }, Modifier.weight(1f), placeholder = { Text("Scrivi") }, singleLine = true)
            Button(enabled = text.isNotBlank(), onClick = { val t = text; text = ""; scope.launch { if (app.service.sendMessage(t)) GuestScheduler.syncSoon(app) } }) { Text("Invia") }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (mine) Color(0xFF16315F) else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 280.dp)) {
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureCard(app: GuestApp, st: GuestState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val chosen = st.activeSources.map { s -> s.id }.toSet()
    SectionCard(title = "Spese dalle notifiche") {
        if (!enabled) Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Consenti l'accesso alle notifiche") }
        else Text("Attivo", color = SpeseTheme.finance.positive)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureSource.entries.filter { s -> s != CaptureSource.SMS }.forEach { s ->
                FilterChip(s.id in chosen, { scope.launch { app.service.setSources(if (s.id in chosen) chosen.filter { id -> id != s.id } else chosen.toList() + s.id) } }, { Text(s.label) })
            }
        }
        Text("In coda ${st.outbox.size} · consegnate ${st.sentCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Avvisi: all'80% del limite, al limite e il riepilogo del lunedì. Per mostrarli serve il permesso di inviare notifiche. */
@Composable
private fun AlertsCard(app: GuestApp, st: GuestState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun change(near: Boolean, limit: Boolean, weekly: Boolean) {
        scope.launch { app.service.setAlerts(near, limit, weekly) }
        if ((near || limit || weekly) && Build.VERSION.SDK_INT >= 33 && !GuestNotifier.canPost(ctx)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    SectionCard(title = "Avvisi") {
        SwitchRow("Al 80% del limite", st.alertNear) { v -> change(v, st.alertLimit, st.alertWeekly) }
        SwitchRow("Al limite", st.alertLimit) { v -> change(st.alertNear, v, st.alertWeekly) }
        SwitchRow("Riepilogo del lunedì", st.alertWeekly) { v -> change(st.alertNear, st.alertLimit, v) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Switch(checked, onChange)
    }
}

/** Come si arriva alla cifra al giorno: tutti i numeri del calcolo, per ricontrollarli. */
@Composable
private fun DetailCard(s: ShareSnapshot) {
    var open by remember { mutableStateOf(false) }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Calcolo", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { open = !open }) { Text(if (open) "Nascondi" else "Mostra") }
        }
        if (open) {
            @Composable fun line(label: String, v: java.math.BigDecimal?, sign: String = "") { if (v != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(sign + Money.eur0(v)) } }
            val zero = java.math.BigDecimal.ZERO
            val left = if (s.cashSpendable != null) (s.cashSpendable!! + (s.incomeRemaining ?: zero) - (s.savingsDue ?: zero) - (s.upcomingTotal ?: zero) - (s.reserve ?: zero)).max(zero)
                else s.limit?.let { l -> l - s.spentToDate - (s.upcomingTotal ?: zero) }
            // i giorni su cui si dividono i soldi sono quelli del mese contabile, non quelli della settimana mostrata
            val base = s.perDayOnTime ?: s.dailyRemaining
            val covered = if (left != null && base != null && base.signum() > 0) left.divide(base, 0, java.math.RoundingMode.HALF_UP).toInt() else null
            if (s.cashSpendable != null) {
                line("Soldi spendibili", s.cashSpendable)
                line("Entrate ancora attese", s.incomeRemaining)
                line("Risparmio da mettere da parte", s.savingsDue, "− ")
                line("Da pagare", s.upcomingTotal, "− ")
                line("Margine", s.reserve, "− ")
            } else {
                line("Entrate attese", s.incomeExpected)
                line("Risparmio", s.savings, "− ")
                line("Margine", s.reserve, "− ")
                line("Limite", s.limit)
                line("Speso", s.spentToDate, "− ")
                line("Da pagare", s.upcomingTotal, "− ")
            }
            line("Resta", left)
            if (s.delayDays != null && s.perDayOnTime != null) Text("Con entrate puntuali: ${Money.eur(s.perDayOnTime!!)} al giorno", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val daysText = if (s.cashSpendable != null && covered != null) (if (s.delayDays != null) "$covered ${if (covered == 1) "giorno" else "giorni"} + ${s.delayDays} di ritardo" else "$covered ${if (covered == 1) "giorno" else "giorni"}") else "${s.days - s.dayIndex} giorni"
            Text("$daysText · ${GuestSummary.perDay(s)} al giorno", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
