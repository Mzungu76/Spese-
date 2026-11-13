package it.spese.domain.repository

import it.spese.core.model.Account
import it.spese.core.model.Category
import it.spese.core.model.Due
import it.spese.core.model.Rule
import it.spese.core.model.SavingsGoal
import it.spese.core.model.Scenario
import it.spese.core.model.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Fotografia coerente di tutti i dati osservabili; le schermate la combinano con i filtri correnti. */
data class AppData(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val dues: List<Due> = emptyList(),
    val scenarios: List<Scenario> = emptyList(),
    val loaded: Boolean = false,
) {
    val categoriesById: Map<Long, Category> by lazy { categories.associateBy { it.id } }
    val accountsById: Map<Long, Account> by lazy { accounts.associateBy { it.id } }
    val baseScenario: Scenario? get() = scenarios.firstOrNull { it.isBase }
    val goal: SavingsGoal get() = baseScenario?.goal ?: SavingsGoal()
    val cuts: Map<Long, Int> get() = baseScenario?.cuts ?: emptyMap()

    fun categoryName(id: Long?): String = categoriesById[id]?.name ?: "Senza categoria"
    fun accountName(id: Long): String = accountsById[id]?.name ?: "?"
}

class AppDataSource(
    private val tx: TransactionRepository,
    private val cats: CategoryRepository,
    private val accounts: AccountRepository,
    private val rules: RuleRepository,
    private val dues: DueRepository,
    private val scenarios: ScenarioRepository,
) {
    fun observe(): Flow<AppData> = combine(
        tx.observeAll(), cats.observeAll(), accounts.observeAll(), rules.observeAll(), dues.observeAll(), scenarios.observeAll(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        AppData(
            values[0] as List<Transaction>, values[1] as List<Category>, values[2] as List<Account>,
            values[3] as List<Rule>, values[4] as List<Due>, values[5] as List<Scenario>, true,
        )
    }
}
