package it.spese.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.FilterStore
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardState(
    val data: AppData = AppData(), val model: DashboardModel? = null, val filter: TxFilter = TxFilter(), val buckets: List<Bucket> = emptyList(),
    /** Confronto di categorie: modalità attiva, scelte e risultato (null senza scelte). */
    val compareMode: Boolean = false, val selected: Set<Long> = emptySet(), val compare: CategoryComparison? = null,
    /** Avanzamento delle spese del periodo in corso rispetto alla media storica (null senza movimenti). */
    val pace: it.spese.domain.finance.PaceReport? = null,
)

/** Consigli per la Home: l'elenco e, nei primi giorni del mese contabile, il mese da chiudere. */
data class HomeAdvice(val insights: List<it.spese.domain.advice.Insight> = emptyList(), val reviewMonth: java.time.YearMonth? = null)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(data: StateFlow<AppData>, private val filters: FilterStore, private val categories: it.spese.domain.repository.CategoryRepository, private val messenger: it.spese.app.common.UiMessenger,
    captureConfig: it.spese.domain.repository.CaptureConfigRepository,
    review: it.spese.domain.finance.SubscriptionReviewRepository,
    recurring: it.spese.domain.repository.RecurringRepository, goals: it.spese.domain.repository.GoalRepository,
    valuations: it.spese.domain.repository.ValuationRepository, transferReview: it.spese.domain.classification.TransferReviewRepository,
    healthSource: it.spese.feature.health.HealthSource, private val widgets: it.spese.domain.widgets.WidgetLayoutRepository,
    reminderRepo: it.spese.domain.reminders.ReminderRepository,
    natures: it.spese.domain.health.IncomeNatureRepository, settings: it.spese.domain.repository.SettingsRepository) : ViewModel() {
    /** Obiettivi con l'avanzamento (per i widget che li mostrano in breve). */
    val goalsProgress: StateFlow<List<it.spese.domain.goals.GoalProgress>> = goals.observeAll().map { g -> it.spese.domain.goals.GoalEngine.all(g, java.time.LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Le sveglie accese con il prossimo scatto, in ordine di tempo. */
    val nextReminders: StateFlow<List<Pair<it.spese.domain.reminders.Reminder, java.time.LocalDateTime>>> = reminderRepo.observe().map { l ->
        val now = java.time.LocalDateTime.now()
        l.filter { r -> r.enabled }.mapNotNull { r -> it.spese.domain.reminders.ReminderEngine.next(r, now)?.let { n -> r to n } }.sortedBy { x -> x.second }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** I widget della Home, nell'ordine scelto (o quello predefinito). */
    val layout: StateFlow<List<it.spese.domain.widgets.WidgetInstance>> = widgets.observe().map { m -> it.spese.domain.widgets.LayoutOps.effective(m, it.spese.domain.widgets.WidgetPage.HOME) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), it.spese.domain.widgets.LayoutOps.default(it.spese.domain.widgets.WidgetPage.HOME))
    /** Saldi di tutti i conti e totale (stessi numeri del Patrimonio). */
    val balances: StateFlow<it.spese.domain.networth.NetWorthReport?> = combine(data, goals.observeAll(), valuations.observeAll()) { d, g, v ->
        if (!d.loaded) null else runCatching { it.spese.domain.networth.NetWorthEngine.compute(d, g, v) }.getOrNull()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** Parere sulla salute del bilancio (null finché i dati non bastano). */
    val health: StateFlow<it.spese.domain.health.HealthReport?> = healthSource.report.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** Quanti giroconti probabili (uscita e entrata dello stesso importo su due conti) aspettano una conferma. */
    val transferPairs: StateFlow<Int> = combine(data, transferReview.observeIgnored()) { d, ign -> runCatching { it.spese.domain.classification.TransferSuggestions.compute(d, ign).size }.getOrDefault(0) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    /** «Disponibile oggi»: quanto si può spendere ancora nel mese, tenendo conto di risparmio e uscite note (conto del filtro, se scelto). */
    val safeToSpend: StateFlow<SafeToSpendReport?> = combine(data, recurring.observeAll(), review.observeIgnored(), filters.filter, goals.observeAll(), natures.observe(), settings.observe().map { s -> Triple(s.budgetMarginPct, s.budgetFromBalance, s.budgetDelayDays) }.distinctUntilChanged(), valuations.observeAll()) { v ->
        @Suppress("UNCHECKED_CAST")
        runCatching { SafeToSpendEngine.forApp(v[0] as AppData, v[1] as List<it.spese.core.model.RecurringRule>, v[2] as Set<String>, (v[3] as it.spese.domain.finance.TxFilter).accountId, goals = v[4] as List<it.spese.core.model.Goal>, incomeOverrides = v[5] as Map<Long, it.spese.domain.health.IncomeNature>, marginPct = (v[6] as Triple<Int, Boolean, Int>).first, valuations = v[7] as List<it.spese.core.model.Valuation>, fromBalance = (v[6] as Triple<Int, Boolean, Int>).second, delayDays = (v[6] as Triple<Int, Boolean, Int>).third) }.getOrNull()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** Consigli in evidenza (i più importanti) e mese da chiudere, se siamo nei primi giorni del nuovo mese contabile. */
    val advice: StateFlow<HomeAdvice> = combine(data, goals.observeAll(), valuations.observeAll(), recurring.observeAll(), review.observeIgnored()) { d, g, v, r, ign ->
        if (!d.loaded) HomeAdvice()
        else runCatching {
            val today = java.time.LocalDate.now()
            val ref = it.spese.domain.finance.AlertEngine.referenceToday(today, d.transactions)
            val month = it.spese.domain.advice.MonthReviewEngine.reviewMonth(d, today)
            HomeAdvice(it.spese.domain.advice.InsightEngine.forApp(d, g, v, r, ign, limit = 5), month?.takeIf { ref == today && d.calendar.dayIndex(ref) <= 14 })
        }.getOrDefault(HomeAdvice())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeAdvice())
    /** Costo mensile di abbonamenti e rate riconosciuti e quante novità (aumenti, addebiti mancanti) ci sono. */
    val subscriptions: StateFlow<it.spese.domain.finance.SubscriptionSummary?> = combine(data, review.observeIgnored()) { d, ign ->
        if (!d.loaded) null else it.spese.domain.finance.SubscriptionDetector.detect(d.transactions, it.spese.domain.finance.SubscriptionDetector.excludedCategories(d.categories)).filter { p -> p.key !in ign }.let { l -> it.spese.domain.finance.SubscriptionDetector.summary(l) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** Notifiche non riconosciute accumulate (0 se sono poche: il promemoria compare solo oltre la soglia). */
    val pendingNotifications: StateFlow<Int> = captureConfig.observe().map { if (it.needsReview) it.unrecognized.size else 0 }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    /** Movimenti segnalati da notifiche senza importo, da completare. */
    val pendingAmounts: StateFlow<Int> = captureConfig.observe().map { it.pending.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val compareMode = MutableStateFlow(false)

    val state: StateFlow<DashboardState> = combine(data, filters.filter, selected, compareMode, goals.observeAll(), recurring.observeAll(), review.observeIgnored(), natures.observe(), settings.observe().map { s -> Triple(s.budgetMarginPct, s.budgetFromBalance, s.budgetDelayDays) }.distinctUntilChanged(), valuations.observeAll()) { arr: Array<Any?> -> arr }
        .mapLatest { a ->
            @Suppress("UNCHECKED_CAST")
            val d = a[0] as AppData; val f = a[1] as TxFilter; val sel = a[2] as Set<Long>; val cm = a[3] as Boolean; val gl = a[4] as List<it.spese.core.model.Goal>
            val rules = a[5] as List<it.spese.core.model.RecurringRule>; val ign = a[6] as Set<String>; val nat = a[7] as Map<Long, it.spese.domain.health.IncomeNature>; val margin = (a[8] as Triple<Int, Boolean, Int>).first; val fromBal = (a[8] as Triple<Int, Boolean, Int>).second; val vals = a[9] as List<it.spese.core.model.Valuation>
            if (!d.loaded) DashboardState(d, null, f)
            else withContext(Dispatchers.Default) {
                val m = DashboardCalculator.compute(d, f)
                DashboardState(d, m, f, (if (f.period == Period.CurrentWeek) d.transactions.maxOfOrNull { it.bookingDate }?.let { last -> val mon = last.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)); TrendBuckets.daily(m.filtered, mon, mon.plusDays(6)) } ?: emptyList() else TrendBuckets.compute(m.filtered, f.calendar)), cm, sel, CategoryComparisonEngine.compute(d, f, sel), runCatching {
                    // senza categorie scelte il limite è il budget giornaliero dell'app (stessa formula di «Disponibile oggi»); con le categorie, i loro budget
                    val limit = if (sel.isEmpty()) it.spese.domain.finance.DailyBudgetEngine.compute(if (f.accountId != null) d.copy(transactions = d.transactions.filter { t -> t.accountId == f.accountId }) else d, rules, ign, gl, nat, margin, cashSource = if (fromBal) { day -> it.spese.domain.finance.CashCalculator.compute(d, f.accountId?.let { id -> setOf(id) }, gl, vals, day) } else null)?.limit else null
                    it.spese.domain.finance.PaceEngine.compute(d, f, categoryIds = sel, savingsMonthly = gl.takeIf { l -> l.isNotEmpty() }?.let { l -> it.spese.domain.goals.GoalEngine.monthlyTotal(l) }, budgetMonthly = limit, budgetIsComputed = limit != null)
                }.getOrNull())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    /** Entra o esce dal confronto di categorie; uscendo si azzera la scelta. */
    fun setCompareMode(on: Boolean) { compareMode.value = on; if (!on) selected.value = emptySet() }
    fun toggleCategory(id: Long) { selected.value = selected.value.let { if (id in it) it - id else it + id }; compareMode.value = true }
    fun clearSelection() { selected.value = emptySet() }

    fun setPeriod(p: Period) = filters.update { it.copy(period = p) }

    /** Imposta la natura (fissa / necessaria / discrezionale) a più categorie in una volta. */
    fun applyNatures(natures: Map<Long, it.spese.core.model.SpendNature>) = viewModelScope.launch {
        try {
            categories.getAll().filter { it.id in natures }.forEach { c -> categories.update(c.copy(nature = natures[c.id])) }
            messenger.show(if (natures.size == 1) "Natura impostata" else "Natura impostata su ${natures.size} categorie")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
