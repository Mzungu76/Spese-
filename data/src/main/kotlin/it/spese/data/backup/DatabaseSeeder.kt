package it.spese.data.backup

import androidx.room.withTransaction
import it.spese.core.common.Money
import it.spese.core.database.AppDatabase
import it.spese.core.database.entity.*
import it.spese.core.model.AccountType
import it.spese.core.model.Category
import it.spese.core.model.GoalMode
import it.spese.domain.classification.DefaultData
import javax.inject.Inject
import javax.inject.Singleton

/** Inserisce al primo avvio le categorie e le regole predefinite (identiche a quelle della versione HTML). */
@Singleton
class DatabaseSeeder @Inject constructor(private val db: AppDatabase) {
    suspend fun ensureSeeded() = db.withTransaction {
        if (db.categories().count() == 0) seed()
        if (db.scenarios().getScenarios().none { it.isBase }) insertBaseScenario()
    }

    private suspend fun insertBaseScenario() {
        db.scenarios().insert(ScenarioEntity(name = "Piano attuale", isBase = true, goalMode = GoalMode.MONTHLY.key, goalValueCents = Money.toCents(Money.of(300)), goalMonths = 12))
    }

    private suspend fun seed() {
        val byName = HashMap<String, Long>()
        DefaultData.categories.forEachIndexed { i, c ->
            val key = when (c.name) {
                DefaultData.FALLBACK_EXPENSE_NAME -> Category.FALLBACK_EXPENSE
                DefaultData.FALLBACK_INCOME_NAME -> Category.FALLBACK_INCOME
                else -> null
            }
            byName[c.name] = db.categories().insert(CategoryEntity(name = c.name, kind = c.kind.key, flexible = c.flexible,
                color = DefaultData.PALETTE[i % DefaultData.PALETTE.size], sortOrder = i, systemKey = key))
        }
        DefaultData.rules.forEachIndexed { i, r ->
            db.rules().insert(RuleEntity(keyword = r.keyword, categoryId = byName.getValue(r.category), sign = r.sign.key,
                minCents = Money.toCents(r.min), maxCents = 0, priority = i))
        }
        if (db.accounts().getAll().isEmpty()) db.accounts().insert(AccountEntity(name = "Conto principale", type = AccountType.CONTO.name))
    }

    /** Ripristina lo stato iniziale (dopo "Azzera"): cancella tutto e reinserisce i dati predefiniti. */
    suspend fun wipeAndSeed() = db.withTransaction {
        db.recurring().deleteAll(); db.transactions().deleteAll(); db.importBatches().deleteAll(); db.documents().deleteAll(); db.dues().deleteAll()
        db.scenarios().deleteAll(); db.rules().deleteAll(); db.categories().deleteAll(); db.accounts().deleteAll()
        seed(); insertBaseScenario()
    }
}
