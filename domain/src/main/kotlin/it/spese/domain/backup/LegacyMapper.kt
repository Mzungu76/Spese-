package it.spese.domain.backup

import it.spese.core.model.Account
import it.spese.core.model.AccountType
import it.spese.core.model.Category
import it.spese.core.model.Due
import it.spese.core.model.Rule
import it.spese.core.model.SavingsGoal
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.core.model.TxOrigin
import it.spese.domain.classification.Classifier
import it.spese.domain.classification.DefaultData
import java.math.BigDecimal

/** Stato completo in termini di modello di dominio (con id assegnati). */
data class DomainSnapshot(
    val accounts: List<Account>,
    val categories: List<Category>,
    val rules: List<Rule>,
    val transactions: List<Transaction>,
    val dues: List<Due>,
    val goal: SavingsGoal,
    /** Tagli del simulatore: id categoria -> %. */
    val cuts: Map<Long, Int>,
)

/** Conversione tra il formato di backup (per nome) e il modello di dominio (per id). */
object LegacyMapper {
    fun parseColor(hex: String): Long {
        val h = hex.trim().removePrefix("#")
        val v = when (h.length) {
            6 -> h.toLongOrNull(16)
            3 -> h.map { "$it$it" }.joinToString("").toLongOrNull(16)
            else -> null
        } ?: 0x888888L
        return 0xFF000000L or v
    }

    fun colorHex(argb: Long): String = "#%06x".format(argb and 0xFFFFFF)

    fun toDomain(s: LegacyState, nowMs: Long = 0): DomainSnapshot {
        // categorie (nomi unici), con fallback garantite
        val cats = ArrayList<Category>()
        var nextId = 1L
        s.cats.distinctBy { it.n }.forEachIndexed { i, c ->
            cats.add(Category(nextId++, c.n, c.k, c.f, parseColor(c.c), s.budget[c.n]?.takeIf { it.signum() > 0 }, c.active, i, null))
        }
        fun ensure(name: String, kind: TxKind, key: String) {
            val existing = cats.indexOfFirst { it.name == name && it.kind == kind }
            if (existing >= 0) cats[existing] = cats[existing].copy(systemKey = key)
            else cats.add(Category(nextId++, name, kind, kind == TxKind.SPESA, 0xFF888888, null, true, cats.size, key))
        }
        ensure(DefaultData.FALLBACK_EXPENSE_NAME, TxKind.SPESA, Category.FALLBACK_EXPENSE)
        ensure(DefaultData.FALLBACK_INCOME_NAME, TxKind.ENTRATA, Category.FALLBACK_INCOME)
        val catByName = cats.associateBy { it.name }

        val accNames = s.tx.map { it.acc }.distinct()
        val accounts = accNames.mapIndexed { i, n -> Account(i + 1L, n, AccountType.CONTO) }
        val accId = accounts.associate { it.name to it.id }

        val rules = s.rules.mapIndexedNotNull { i, r ->
            val c = catByName[r.cat] ?: return@mapIndexedNotNull null
            Rule(r.id.takeIf { it > 0 } ?: (i + 1L), r.kw, c.id, r.sg, r.min, r.max, i, true)
        }
        val classifier = Classifier(cats, rules)
        val seen = HashSet<String>()
        val txs = s.tx.filter { seen.add(it.id) }.mapIndexed { i, t ->
            val manualCat = s.ov[t.id]?.let { catByName[it]?.id }
            val cl = classifier.classify(t.t, t.a, manualCat)
            Transaction(i + 1L, t.id, accId.getValue(t.acc), t.d, t.v, t.t, t.a, cl.kind, cl.categoryId, cl.manual, cl.ruleId,
                t.merchant, t.notes, runCatching { TxOrigin.valueOf(t.origin ?: "BACKUP") }.getOrDefault(TxOrigin.BACKUP), null, nowMs)
        }
        val dues = s.due.mapIndexed { i, d -> Due(i + 1L, d.id, d.name, d.amt, d.next, d.freq) }
        val goal = SavingsGoal(s.goal.mode, s.goal.value, s.goal.months, s.goal.income)
        val cuts = s.cuts.mapNotNull { (k, v) -> catByName[k]?.let { it.id to v } }.toMap()
        return DomainSnapshot(accounts, cats, rules, txs, dues, goal, cuts)
    }

    fun fromDomain(d: DomainSnapshot, mt: Map<String, Long> = emptyMap(), docNames: Map<Long, String> = emptyMap()): LegacyState {
        val acc = d.accounts.associate { it.id to it.name }
        val cat = d.categories.associate { it.id to it.name }
        val tx = d.transactions.sortedWith(compareByDescending<Transaction> { it.bookingDate }.thenByDescending { it.id }).map {
            LegacyTx(it.externalId, it.bookingDate, it.valueDate, it.amount, it.description, acc[it.accountId] ?: "Conto principale",
                it.notes, it.merchantOverride, it.origin.name, it.documentId?.let { id -> docNames[id] })
        }
        val cats = d.categories.sortedBy { it.sortOrder }.map { LegacyCat(it.name, it.kind, it.flexible, colorHex(it.color), it.active) }
        val rules = d.rules.sortedBy { it.priority }.mapNotNull { r ->
            cat[r.categoryId]?.let { LegacyRule(r.id, r.keyword, it, r.sign, r.min, r.max) }
        }
        val ov = d.transactions.filter { it.manualCategory && it.categoryId != null }.associate { it.externalId to cat.getValue(it.categoryId!!) }
        val budget = d.categories.filter { (it.budgetMonthly ?: BigDecimal.ZERO).signum() > 0 }.associate { it.name to it.budgetMonthly!! }
        val due = d.dues.map { LegacyDue(it.legacyId, it.name, it.amount, it.next, it.frequency) }
        val cuts = d.cuts.mapNotNull { (k, v) -> cat[k]?.let { it to v } }.toMap()
        return LegacyState(tx, cats, rules, due, ov, budget, LegacyGoal(d.goal.mode, d.goal.value, d.goal.months, d.goal.expectedIncome), cuts, mt)
    }
}
