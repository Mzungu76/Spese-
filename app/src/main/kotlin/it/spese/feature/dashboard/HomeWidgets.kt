package it.spese.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.core.common.Dates
import it.spese.core.model.Transaction
import it.spese.domain.classification.merchant
import it.spese.domain.reminders.ReminderKind
import it.spese.core.common.Money
import it.spese.core.designsystem.chart.GapAreaChart
import it.spese.core.designsystem.chart.GapPoint
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.FinanceColors
import it.spese.core.model.TxKind
import it.spese.domain.finance.DashboardModel
import it.spese.domain.finance.Period
import it.spese.domain.finance.PeriodResolver
import it.spese.domain.finance.SafeToSpendReport
import it.spese.domain.finance.SavingsOpportunities
import it.spese.domain.finance.SubscriptionSummary
import it.spese.domain.goals.GoalProgress
import it.spese.domain.health.HealthReport
import it.spese.domain.reminders.Reminder
import java.time.LocalDateTime
import it.spese.domain.networth.NetWorthReport
import it.spese.domain.widgets.WidgetInstance
import it.spese.feature.categories.CategoryRankRow
import it.spese.feature.categories.ComparisonSection
import it.spese.feature.categories.OpportunitiesCard
import it.spese.feature.categories.WatchCard
import java.math.BigDecimal

/** Dove portano i tocchi sui widget della Home. */
class HomeCallbacks(
    val onOpenAlerts: () -> Unit, val onOpenCategory: (Long?) -> Unit, val onOpenNetWorth: () -> Unit, val onOpenCategories: () -> Unit, val onOpenTransfers: () -> Unit,
    val onOpenTrends: () -> Unit, val onOpenCapture: () -> Unit, val onOpenSubscriptions: () -> Unit, val onOpenAdvice: () -> Unit, val onOpenTransfersReview: () -> Unit,
    val onOpenUnassigned: () -> Unit, val onOpenHealth: () -> Unit, val onOpenReview: () -> Unit,
    val onAdd: () -> Unit = {}, val onImport: () -> Unit = {}, val onScan: () -> Unit = {}, val onOpenReminders: () -> Unit = {}, val onOpenGoals: () -> Unit = {}, val onOpenBudgets: () -> Unit = {},
)

/** Tutto ciò che i widget della Home leggono: lo stato condiviso (periodo, selezione di categorie) e i dati già calcolati. */
class HomeCtx(
    val st: DashboardState, val m: DashboardModel, val vm: DashboardViewModel, val fin: FinanceColors,
    val health: HealthReport?, val safe: SafeToSpendReport?, val advice: HomeAdvice, val balances: NetWorthReport?, val subs: SubscriptionSummary?,
    val transferCount: Int, val pendingAmounts: Int, val pendingNotifs: Int, val alertCount: Int,
    val points: List<GapPoint>, val shownPeriod: Period, val catKind: MutableState<TxKind>, val cb: HomeCallbacks,
    val goals: List<GoalProgress> = emptyList(), val reminders: List<Pair<Reminder, LocalDateTime>> = emptyList(),
)

private fun Modifier.homeCard() = this.padding(horizontal = 16.dp, vertical = 8.dp)

/** Un widget della Home: aggiunge alla lista le sue righe (nessuna, se non c'è nulla da mostrare). */
fun LazyListScope.homeWidget(inst: WidgetInstance, c: HomeCtx) {
    val k = inst.uid
    val fin = c.fin
    val s = c.m.stats
    when (inst.widgetId) {
        "section" -> { item(key = k) { SectionHeader(inst.options["title"].orEmpty(), Modifier.padding(horizontal = 24.dp)) } }
        "health" -> { c.health?.let { h -> item(key = k) { HealthCard(h, c.cb.onOpenHealth, Modifier.homeCard()) } } }
        "safe" -> { c.safe?.let { r -> item(key = k) { SafeToSpendCard(r, Modifier.homeCard()) } } }
        "advice" -> {
            c.advice.reviewMonth?.let { month ->
                item(key = "$k-review") {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = c.cb.onOpenReview).padding(horizontal = 18.dp, vertical = 14.dp).testTag("review_link"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Default.EventAvailable, null, tint = fin.positive)
                        Column(Modifier.weight(1f)) {
                            Text("Chiudi ${Dates.monthLabelLong(month)}", style = MaterialTheme.typography.titleMedium)
                            Text("Quanto hai risparmiato e cosa fare ora", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "$k-advice") {
                val top = c.advice.insights.firstOrNull()
                Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = c.cb.onOpenAdvice).padding(horizontal = 18.dp, vertical = 14.dp).testTag("advice_link"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.Lightbulb, null, tint = if ((top?.level ?: 0) >= 1) fin.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(top?.title ?: "Consigli e guida", style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(if (top != null && c.advice.insights.size > 1) "e altri ${c.advice.insights.size - 1} consigli" else if (top != null) top.text else "Piccola guida sui tuoi numeri", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "balances" -> { c.balances?.takeIf { b -> b.accounts.isNotEmpty() }?.let { b -> item(key = k) { BalancesCard(b, c.st.data.managedAccountIds, c.cb.onOpenNetWorth, Modifier.homeCard()) } } }
        "hero" -> {
            item(key = k) {
                val saved = s.net.signum() >= 0
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
                    Eyebrow(if (saved) "Hai messo da parte" else "Hai speso in più di quanto incassi")
                    CountUpMoney(s.net.abs()) { v -> MoneyText(v, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, colored = false, decimals = true) }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.savingsRatePercent?.let { r ->
                            Text("$r%", Modifier.background(if (r >= 0) fin.positive.copy(alpha = 0.16f) else fin.negative.copy(alpha = 0.16f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge, color = if (r >= 0) fin.positive else fin.negative, fontWeight = FontWeight.SemiBold)
                            Text("di quello che incassi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        "cashflow" -> {
            val cmp = c.st.compare
            if (cmp == null) {
                item(key = "$k-legend") {
                    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        LegendItem("Entrate", Money.eur0(s.income), fin.positive)
                        LegendItem("Spese", Money.eur0(s.expense), fin.negative)
                    }
                }
                item(key = "$k-chart") { GapAreaChart(c.points, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), height = 300.dp) }
            } else item(key = "$k-compare") { ComparisonSection(cmp, c.vm::toggleCategory, c.vm::clearSelection, Modifier.padding(top = 20.dp, bottom = 8.dp)) }
        }
        "period" -> {
            item(key = k) {
                PillSegmented(listOf<Pair<Period, String>>(Period.CurrentWeek to "Sett.", Period.CurrentMonth to "Mese", Period.Last3Months to "3 mesi", Period.YearToDate to "Anno", Period.All to "Tutto"), c.shownPeriod, c.vm::setPeriod, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            if (!c.st.filter.calendar.isSolar && c.st.data.ledger.isNotEmpty()) item(key = "$k-note") {
                val r = PeriodResolver.resolve(c.shownPeriod, c.st.data.ledger.minOf { t -> t.bookingDate }, c.st.data.ledger.maxOf { t -> t.bookingDate }, c.st.filter.calendar)
                Text("Mese contabile dal giorno ${c.st.filter.calendar.startDay}" + (r.from?.let { f -> r.to?.let { t -> " · periodo ${Dates.fmt(f)} – ${Dates.fmt(t)}" } } ?: ""),
                    Modifier.padding(horizontal = 24.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "pace" -> { c.st.pace?.let { pace -> item(key = k) { PaceSection(pace, Modifier.homeCard()) } } }
        "watch" -> {
            val watch = SavingsOpportunities.surging(c.m.categories)
            if (watch.isNotEmpty()) item(key = k) { WatchCard(watch, c.m.previousLabel, { id -> c.cb.onOpenCategory(id) }, Modifier.homeCard()) }
        }
        "opps" -> {
            val opps = SavingsOpportunities.compute(c.m.categories, 20)
            if (opps.isNotEmpty()) item(key = k) { OpportunitiesCard(opps, { id -> c.cb.onOpenCategory(id) }, Modifier.homeCard()) }
        }
        "categories" -> {
            val catKind = c.catKind.value
            item(key = "$k-head") {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dove vanno i soldi", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (c.st.compareMode) TextButton(onClick = { c.vm.setCompareMode(false) }) { Text("Fine") }
                        else TextButton(onClick = { c.vm.setCompareMode(true) }) { Icon(Icons.Default.CompareArrows, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Confronta") }
                    }
                    if (c.st.compareMode && c.st.selected.isEmpty()) Text("Tocca le categorie da confrontare tra loro e con le entrate del periodo.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.m.previousLabel != null) Text("Variazioni " + c.m.previousLabel, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PillSegmented(listOf(TxKind.SPESA to "Spese", TxKind.ENTRATA to "Entrate"), catKind, { v -> c.catKind.value = v }, Modifier.padding(top = 12.dp))
                }
            }
            val rows = if (catKind == TxKind.SPESA) c.m.categories else c.m.incomeCategories
            val maxTotal = rows.maxOfOrNull { r -> r.total } ?: BigDecimal.ONE
            if (rows.isEmpty()) item(key = "$k-empty") { Text(if (catKind == TxKind.SPESA) "Nessuna spesa nel periodo." else "Nessuna entrata nel periodo.", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows.take(6), key = { r -> "$k-cat-$catKind-${r.category?.id}" }) { r ->
                val rid = r.category?.id
                CategoryRankRow(r, catKind, maxTotal, { if (c.st.compareMode && rid != null) c.vm.toggleCategory(rid) else c.cb.onOpenCategory(rid) },
                    Modifier.testTag("rank-$rid"), selectable = c.st.compareMode, selected = rid != null && rid in c.st.selected, onLongClick = { if (rid != null) c.vm.toggleCategory(rid) },
                    budget = if (catKind == TxKind.SPESA && c.st.filter.period == Period.CurrentMonth) c.m.budgets.firstOrNull { b -> b.category.id == r.category?.id } else null)
            }
            item(key = "$k-trends") { TextButton(onClick = c.cb.onOpenTrends, Modifier.padding(horizontal = 12.dp)) { Icon(Icons.Default.Timeline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tendenze e proiezioni per categoria") } }
            if (rows.size > 6) item(key = "$k-more") { TextButton(onClick = c.cb.onOpenCategories, Modifier.padding(horizontal = 12.dp)) { Text("Vedi tutte le ${rows.size} categorie") } }
            if (s.transfers.signum() != 0) item(key = "$k-transfers") {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = c.cb.onOpenTransfers).padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.SwapHoriz, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Giroconti esclusi dai totali", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(s.transfers.abs(), decimals = false, muted = true)
                }
            }
        }
        "alerts" -> {
            item(key = k) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = c.cb.onOpenAlerts).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val dot = when { c.m.alerts.any { a -> a.level >= 2 } -> MaterialTheme.colorScheme.error; c.alertCount > 0 -> fin.warning; else -> fin.positive }
                    Box(Modifier.size(11.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
                    Text(if (c.alertCount > 0) (if (c.alertCount == 1) "1 cosa da guardare" else "${c.alertCount} cose da guardare") else "Tutto sotto controllo", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "unassigned" -> {
            if (c.st.data.unassigned.isNotEmpty()) item(key = k) {
                val n = c.st.data.unassigned.size
                Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = c.cb.onOpenUnassigned).padding(horizontal = 18.dp, vertical = 14.dp).testTag("unassigned_link"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = fin.warning)
                    Column(Modifier.weight(1f)) {
                        Text(if (n == 1) "1 movimento senza conto" else "$n movimenti senza conto", style = MaterialTheme.typography.titleMedium)
                        Text("Sono fuori da saldi, entrate e spese finché non scegli il conto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "transfer_pairs" -> {
            if (c.transferCount > 0) item(key = k) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = c.cb.onOpenTransfersReview).padding(horizontal = 18.dp, vertical = 14.dp).testTag("transfer_pairs_link"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.SwapHoriz, null, tint = fin.warning)
                    Column(Modifier.weight(1f)) {
                        Text(if (c.transferCount == 1) "1 giroconto probabile" else "${c.transferCount} giroconti probabili", style = MaterialTheme.typography.titleMedium)
                        Text("Spostamenti tra i tuoi conti: confermali per toglierli da entrate e spese", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "pending_amounts" -> {
            if (c.pendingAmounts > 0) item(key = k) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = c.cb.onOpenCapture).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.EditNote, null, tint = fin.warning)
                    Column(Modifier.weight(1f)) {
                        Text(if (c.pendingAmounts == 1) "1 movimento da regolarizzare" else "${c.pendingAmounts} movimenti da regolarizzare", style = MaterialTheme.typography.titleMedium)
                        Text("La notifica non riporta la cifra: inseriscila quando vuoi o attendi l'estratto conto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "notif_review" -> {
            if (c.pendingNotifs > 0) item(key = k) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = c.cb.onOpenCapture).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.NotificationsActive, null, tint = fin.warning)
                    Column(Modifier.weight(1f)) {
                        Text("${c.pendingNotifs} notifiche da controllare", style = MaterialTheme.typography.titleMedium)
                        Text("Indica quali ignorare e quali sono pagamenti", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "subs" -> {
            c.subs?.takeIf { sub -> sub.count > 0 }?.let { sub ->
                item(key = k) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = c.cb.onOpenSubscriptions).padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Default.Autorenew, null, tint = if (sub.news > 0) fin.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text("Abbonamenti e rate: ${Money.eur0(sub.monthly)}/mese", style = MaterialTheme.typography.titleMedium)
                            Text(if (sub.news > 0) "${sub.news} novità: aumenti di prezzo o addebiti mancanti" else "${sub.count} voci · ${Money.eur0(sub.annual)} all'anno", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        "net_summary" -> {
            c.balances?.let { r ->
                item(key = k) {
                    SectionCard(Modifier.homeCard().clickable(onClick = c.cb.onOpenNetWorth), title = "Patrimonio") {
                        MoneyText(r.total, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, colored = false, decimals = false)
                        r.change?.let { ch -> Text((if (ch.signum() >= 0) "+" else "−") + Money.eur0(ch.abs()) + " nei mesi scorsi", style = MaterialTheme.typography.bodyMedium, color = if (ch.signum() >= 0) fin.positive else fin.negative) }
                        Text((if (r.blocked.signum() > 0) "Disponibile ${Money.eur0(r.spendable)} · Bloccati negli obiettivi ${Money.eur0(r.blocked)}" else "Liquidità ${Money.eur0(r.liquidity)}") + " · Investimenti ${Money.eur0(r.investments)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        "goals_progress" -> {
            item(key = k) {
                SectionCard(Modifier.homeCard().clickable(onClick = c.cb.onOpenGoals), title = "Obiettivi") {
                    if (c.goals.isEmpty()) Text("Nessun obiettivo: tocca per crearne uno.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    c.goals.take(3).forEach { p ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.goal.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("${p.percent}%", style = MaterialTheme.typography.labelLarge)
                            }
                            LinearProgressIndicator(progress = { p.percent / 100f }, Modifier.fillMaxWidth().height(6.dp), color = fin.positive, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                            Text("${Money.eur0(p.goal.saved)} di ${Money.eur0(p.goal.target)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        "budgets" -> {
            val used = c.m.budgets.filter { u -> u.budget.signum() > 0 }.take(4)
            if (used.isNotEmpty()) item(key = k) {
                SectionCard(Modifier.homeCard().clickable(onClick = c.cb.onOpenBudgets), title = "Stato dei budget") {
                    used.forEach { u ->
                        val over = u.spent > u.budget
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(u.category.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("${Money.eur0(u.spent)} / ${Money.eur0(u.budget)}", style = MaterialTheme.typography.labelLarge, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                            LinearProgressIndicator(progress = { (u.spent.toFloat() / u.budget.toFloat()).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp), color = if (over) MaterialTheme.colorScheme.error else fin.positive, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                        }
                    }
                }
            }
        }
        "dues" -> {
            val next = c.m.upcoming.take(5)
            if (next.isNotEmpty()) item(key = k) {
                SectionCard(Modifier.homeCard(), title = "Prossime scadenze") {
                    next.forEach { u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(u.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(when { u.daysLeft < 0 -> "Scaduta da ${-u.daysLeft} giorni"; u.daysLeft == 0 -> "Oggi"; u.daysLeft == 1 -> "Domani"; else -> "Tra ${u.daysLeft} giorni" } + " · " + Dates.fmt(u.date),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(u.amount.abs(), decimals = false, colored = false)
                        }
                    }
                }
            }
        }
        "top_expenses" -> {
            val top = c.m.biggestExpenses.take(5)
            if (top.isNotEmpty()) item(key = k) {
                SectionCard(Modifier.homeCard(), title = "Spese più grandi") {
                    top.forEach { x ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(x.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(Dates.fmt(x.bookingDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(x.amount.abs(), decimals = false, colored = false)
                        }
                    }
                }
            }
        }
        "top_merchants" -> {
            val top = c.m.merchants.take(5)
            if (top.isNotEmpty()) item(key = k) {
                SectionCard(Modifier.homeCard(), title = "Esercenti principali") {
                    top.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.first, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(if (e.second.count == 1) "1 volta" else "${e.second.count} volte", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(e.second.total.abs(), decimals = false, colored = false)
                        }
                    }
                }
            }
        }
        "recent_tx" -> {
            val last = c.st.data.ledger.sortedWith(compareByDescending<Transaction> { t -> t.bookingDate }.thenByDescending { t -> t.id }).take(5)
            if (last.isNotEmpty()) item(key = k) {
                SectionCard(Modifier.homeCard(), title = "Ultimi movimenti") {
                    last.forEach { x ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(x.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(Dates.fmt(x.bookingDate) + " · " + c.st.data.accountName(x.accountId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            MoneyText(x.amount, signed = true, colored = x.amount.signum() > 0 && x.kind == TxKind.ENTRATA, muted = x.kind == TxKind.TRASFERIMENTO, decimals = false)
                        }
                    }
                }
            }
        }
        "receipts" -> {
            val withDoc = c.st.data.ledger.filter { t -> t.documentId != null }.sortedWith(compareByDescending<Transaction> { t -> t.bookingDate }.thenByDescending { t -> t.id }).take(5)
            if (withDoc.isNotEmpty()) item(key = k) {
                SectionCard(Modifier.homeCard(), title = "Ultimi scontrini") {
                    withDoc.forEach { x ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("📎 " + x.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(Dates.fmt(x.bookingDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MoneyText(x.amount.abs(), decimals = false, colored = false)
                        }
                    }
                }
            }
        }
        "reminders_next" -> {
            val next = c.reminders.take(3)
            if (next.isNotEmpty()) item(key = k) {
                SectionCard(Modifier.homeCard().clickable(onClick = c.cb.onOpenReminders), title = "Prossime sveglie") {
                    next.forEach { (r, at) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Alarm, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title.ifBlank { if (r.kind == ReminderKind.IMPORT) "Importa estratto conto" else "Promemoria" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(Dates.fmt(at.toLocalDate()) + " alle " + "%02d:%02d".format(at.hour, at.minute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        "shortcuts" -> {
            item(key = k) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = c.cb.onAdd, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Aggiungi", maxLines = 1) }
                    FilledTonalButton(onClick = c.cb.onImport, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Importa", maxLines = 1) }
                    FilledTonalButton(onClick = c.cb.onScan, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Default.DocumentScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Scansiona", maxLines = 1) }
                    FilledTonalButton(onClick = c.cb.onOpenReminders, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Default.Alarm, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Sveglie", maxLines = 1) }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun LegendItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Il numero grande «sale» dal valore precedente a quello nuovo (stile app bancarie) invece di cambiare di colpo. */
@Composable
private fun CountUpMoney(value: BigDecimal, content: @Composable (BigDecimal) -> Unit) {
    var from by remember { mutableStateOf(BigDecimal.ZERO) }
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(value) {
        val start = from
        t.snapTo(0f)
        t.animateTo(1f, androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) { from = start + (value - start).multiply(BigDecimal(value.signum().let { _ -> this.value.toDouble() })) }
        from = value
    }
    content(if (t.value >= 1f) value else from.setScale(2, java.math.RoundingMode.HALF_UP))
}


/**
 * I dati dei widget condivisi, per le pagine che non sono la Home (Movimenti, Obiettivi, Patrimonio).
 * Restituisce `null` se la pagina non ne usa (`needed` = false), se mancano i tocchi o se i dati non sono pronti.
 */
@Composable
fun rememberHomeCtx(cb: HomeCallbacks?, needed: Boolean): HomeCtx? {
    val vm: DashboardViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val catKind = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(TxKind.SPESA) }
    if (!needed || cb == null) return null
    val st by vm.state.collectAsStateWithLifecycle()
    val pendingNotifs by vm.pendingNotifications.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val pendingAmounts by vm.pendingAmounts.collectAsStateWithLifecycle()
    val safe by vm.safeToSpend.collectAsStateWithLifecycle()
    val advice by vm.advice.collectAsStateWithLifecycle()
    val transferCount by vm.transferPairs.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val goals by vm.goalsProgress.collectAsStateWithLifecycle()
    val reminders by vm.nextReminders.collectAsStateWithLifecycle()
    val fin = it.spese.core.designsystem.theme.SpeseTheme.finance
    val m = st.model
    return if (!st.data.loaded || m == null) null else HomeCtx(
        st = st, m = m, vm = vm, fin = fin, health = health, safe = safe, advice = advice, balances = balances, subs = subs,
        transferCount = transferCount, pendingAmounts = pendingAmounts, pendingNotifs = pendingNotifs, alertCount = m.alerts.count { a -> a.level >= 1 },
        points = st.buckets.map { b -> GapPoint(b.label, b.income, b.expense, b.title) },
        shownPeriod = when (val per = st.filter.period) { Period.All, Period.YearToDate, Period.Last3Months, Period.CurrentMonth, Period.CurrentWeek -> per; else -> Period.All },
        catKind = catKind, cb = cb, goals = goals, reminders = reminders,
    )
}

/** Un widget condiviso dentro una pagina che non è una lista a scorrimento (Movimenti): un piccolo elenco che prende solo lo spazio che gli serve. */
@Composable
fun SharedWidgetBlock(inst: WidgetInstance, c: HomeCtx, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth().heightIn(max = 420.dp), userScrollEnabled = false) { homeWidget(inst, c) }
}
