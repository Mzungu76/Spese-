package it.spese.domain.usecase

import it.spese.core.model.RecurringRule
import it.spese.core.model.TxKind
import it.spese.core.model.TxOrigin
import it.spese.domain.classification.Classifier
import it.spese.domain.recurring.RecurringEngine
import it.spese.domain.repository.*
import java.time.LocalDate
import javax.inject.Inject

/**
 * Crea i movimenti dovuti dalle entrate e spese ricorrenti, fino a oggi. Ogni occorrenza ha un identificativo fisso
 * (`rec|regola|data`) e la regola ricorda l'ultima data creata: quel che l'utente elimina non viene ricreato.
 * I movimenti nascono provvisori (origine RECURRING) e passano da Verifica, salvo `autoConfirm`.
 */
class GenerateRecurringUseCase @Inject constructor(
    private val tx: TransactionRepository, private val accounts: AccountRepository, private val categories: CategoryRepository,
    private val rules: RuleRepository, private val recurring: RecurringRepository,
) {
    suspend operator fun invoke(today: LocalDate = LocalDate.now()): Int {
        val all = recurring.getAll().filter { it.enabled }
        if (all.isEmpty()) return 0
        val accs = accounts.getAll()
        val cats = categories.getAll()
        val classifier = Classifier(cats, rules.getAll())
        var created = 0
        for (r in all) {
            val dates = RecurringEngine.due(r, today)
            if (dates.isEmpty()) continue
            val acc = r.accountId?.let { id -> accs.firstOrNull { it.id == id } }       // senza conto (o conto eliminato): il movimento va in Verifica
            val signed = if (r.kind == TxKind.SPESA) r.amount.negate() else r.amount
            val fixedCat = r.categoryId?.let { id -> cats.firstOrNull { it.id == id } }
            val items = dates.map { d ->
                val c = if (fixedCat != null) it.spese.domain.classification.Classification(fixedCat.id, fixedCat.kind, true, null) else classifier.classify(r.name, signed, null)
                val ext = "rec|${r.id}|$d"
                NewTransaction(ext, ext, acc?.id, d, d, r.name, signed, c.kind, c.categoryId, c.manual, c.ruleId, null, "Ricorrente · ${r.name}", TxOrigin.RECURRING)
            }
            created += tx.insertAll(items)
            if (r.autoConfirm) {
                val ids = items.mapNotNull { i -> tx.getAll().firstOrNull { it.externalId == i.externalId }?.id }
                if (ids.isNotEmpty()) tx.setReviewed(ids, true)
            }
            recurring.markGenerated(r.id, dates.max())
        }
        return created
    }
}

/** Salva una regola ricorrente (con controlli) e crea subito le occorrenze già dovute. */
class SaveRecurringUseCase @Inject constructor(private val recurring: RecurringRepository, private val generate: GenerateRecurringUseCase) {
    suspend operator fun invoke(r: RecurringRule): Int {
        require(r.name.isNotBlank()) { "Dai un nome al movimento ricorrente." }
        require(r.amount.signum() > 0) { "Indica un importo maggiore di zero." }
        require(r.kind != TxKind.TRASFERIMENTO) { "Scegli entrata o spesa." }
        val end = r.endDate
        require(end == null || !end.isBefore(r.startDate)) { "La data di fine è prima di quella di inizio." }
        // se si cambia l'inizio, si riparte da zero: altrimenti si manterrebbe una "ultima data" che non c'entra più
        val old = recurring.getAll().firstOrNull { it.id == r.id }
        val fixed = if (old != null && old.startDate != r.startDate) r.copy(lastGenerated = null) else r
        recurring.save(fixed)
        return generate()
    }
}
