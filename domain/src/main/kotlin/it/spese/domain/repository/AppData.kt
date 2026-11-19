package it.spese.domain.repository

import it.spese.core.model.Account
import it.spese.core.model.CashQuota
import it.spese.core.model.TransactionSplit
import it.spese.core.model.Category
import it.spese.core.model.Due
import it.spese.core.model.Rule
import it.spese.core.model.SavingsGoal
import it.spese.core.model.Scenario
import it.spese.core.model.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Fotografia coerente di tutti i dati osservabili; le schermate la combinano con i filtri correnti. */
data class AppData(
    /** Movimenti per le analisi: quelli divisi sono sostituiti dalle loro parti (stesso id, importo e categoria della parte). */
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val dues: List<Due> = emptyList(),
    val scenarios: List<Scenario> = emptyList(),
    val loaded: Boolean = false,
    /** Movimenti così come sono (importo intero, anche se divisi): per elenco, modifica ed esportazione. */
    val ledger: List<Transaction> = transactions,
    val splits: Map<Long, List<TransactionSplit>> = emptyMap(),
    val quotas: List<CashQuota> = emptyList(),
    /** Mese contabile scelto dall'utente (calendario di default). */
    val calendar: it.spese.core.common.MonthCalendar = it.spese.core.common.MonthCalendar.SOLAR,
    /** Movimenti senza conto: sono in Verifica e NON entrano in `transactions` né in `ledger`, quindi in nessun saldo, totale o analisi. */
    val unassigned: List<Transaction> = emptyList(),
    /** Conti «gestiti da altri» (denaro di famiglia): restano nel patrimonio e in `ledger`, ma i loro movimenti non entrano in `transactions`, quindi in budget, salute, ritmo e medie. */
    val managedAccountIds: Set<Long> = emptySet(),
    /** Come `transactions` ma di tutti i conti, anche quelli gestiti da altri (per elencare i movimenti di quel conto). */
    private val allTransactions: List<Transaction>? = null,
) {
    val everything: List<Transaction> get() = allTransactions ?: transactions
    val categoriesById: Map<Long, Category> by lazy { categories.associateBy { it.id } }
    val accountsById: Map<Long, Account> by lazy { accounts.associateBy { it.id } }
    val baseScenario: Scenario? get() = scenarios.firstOrNull { it.isBase }
    val goal: SavingsGoal get() = baseScenario?.goal ?: SavingsGoal()
    val cuts: Map<Long, Int> get() = baseScenario?.cuts ?: emptyMap()

    fun categoryName(id: Long?): String = categoriesById[id]?.name ?: "Senza categoria"
    fun accountName(id: Long?): String = if (id == null) "Senza conto" else accountsById[id]?.name ?: "?"
}

class AppDataSource(
    private val tx: TransactionRepository,
    private val cats: CategoryRepository,
    private val accounts: AccountRepository,
    private val rules: RuleRepository,
    private val dues: DueRepository,
    private val scenarios: ScenarioRepository,
    private val quotas: CashQuotaRepository,
    private val settings: SettingsRepository? = null,
    private val managed: Flow<Set<Long>>? = null,
) {
    fun observe(): Flow<AppData> = combine(
        tx.observeAll(), cats.observeAll(), accounts.observeAll(), rules.observeAll(), dues.observeAll(), scenarios.observeAll(),
        tx.observeSplits(), quotas.observeAll(),
        (settings?.observe() ?: kotlinx.coroutines.flow.flowOf(AppSettings())).map { Triple(it.monthStartDay, it.monthAnchorCategoryId, it.monthAnchorAmount) }.distinctUntilChanged(),
        managed ?: kotlinx.coroutines.flow.flowOf(emptySet<Long>()),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<Transaction>
        // la contabilità conta solo i movimenti con un conto; gli altri aspettano in Verifica
        val raw = all.filter { it.accountId != null }
        val unassigned = all.filter { it.accountId == null }
        val categories = values[1] as List<Category>
        val splits = (values[6] as List<TransactionSplit>).groupBy { it.txId }
        val managedIds = values[9] as Set<Long>
        val byId = categories.associateBy { c -> c.id }
        val everything = it.spese.domain.finance.SplitExpander.expand(raw, splits, byId)
        val (startDay, anchorId, anchorAmount) = values[8] as Triple<Int, Long, Int>
        val cal = it.spese.domain.finance.MonthAnchor.calendar(startDay, anchorId, raw, byId, java.time.LocalDate.now(), java.math.BigDecimal(anchorAmount))
        // le analisi usano il mese contabile scelto per le entrate; saldi ed elenchi la data reale
        val analytic = it.spese.domain.finance.AccountingMonth.shift(everything, cal)
        AppData(
            if (managedIds.isEmpty()) analytic else analytic.filter { t -> t.accountId !in managedIds }, categories, values[2] as List<Account>,
            values[3] as List<Rule>, values[4] as List<Due>, values[5] as List<Scenario>, true,
            raw, splits, values[7] as List<CashQuota>, cal, unassigned,
            managedIds, if (managedIds.isEmpty() && analytic === everything) null else everything,
        )
    }
}
