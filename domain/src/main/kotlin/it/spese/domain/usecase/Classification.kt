package it.spese.domain.usecase

import it.spese.core.model.AmountSign
import it.spese.core.model.Category
import it.spese.core.model.Rule
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.core.model.TxOrigin
import it.spese.domain.classification.Classifier
import it.spese.domain.classification.DefaultData
import it.spese.domain.classification.Duplicates
import it.spese.domain.repository.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** Riclassifica tutti i movimenti con le regole correnti; le assegnazioni manuali prevalgono. Aggiorna solo ciò che cambia. */
class ReclassifyAllUseCase @Inject constructor(
    private val tx: TransactionRepository,
    private val categories: CategoryRepository,
    private val rules: RuleRepository,
) {
    suspend operator fun invoke(): Int {
        val classifier = Classifier(categories.getAll(), rules.getAll())
        val all = tx.getAll()
        val updates = all.mapNotNull { t ->
            val c = classifier.classify(t.description, t.amount, if (t.manualCategory) t.categoryId else null)
            if (c.categoryId != t.categoryId || c.kind != t.kind || c.manual != t.manualCategory || c.ruleId != t.ruleId)
                ClassificationUpdate(t.id, c.categoryId, c.kind, c.manual, c.ruleId) else null
        }
        if (updates.isNotEmpty()) tx.applyClassifications(updates)
        return updates.size
    }
}

data class TransactionDraft(
    val id: Long = 0,
    /** Il conto è facoltativo solo per i movimenti automatici (notifiche, ricorrenze): senza conto vanno in Verifica. */
    val accountId: Long?,
    val bookingDate: LocalDate,
    val valueDate: LocalDate = bookingDate,
    val description: String,
    /** Importo con segno: negativo = uscita. */
    val amount: BigDecimal,
    /** Categoria scelta dall'utente; null = classificazione automatica. */
    val categoryId: Long? = null,
    val merchant: String? = null,
    val notes: String = "",
    val documentId: Long? = null,
    val origin: TxOrigin = TxOrigin.MANUAL,
)

/** Inserimento e modifica manuale di un movimento. */
class SaveTransactionUseCase @Inject constructor(
    private val tx: TransactionRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val rules: RuleRepository,
) {
    suspend operator fun invoke(d: TransactionDraft): Long {
        require(d.description.isNotBlank()) { "La descrizione è obbligatoria." }
        require(d.amount.signum() != 0) { "L'importo non può essere zero." }
        val classifier = Classifier(categories.getAll(), rules.getAll())
        val c = classifier.classify(d.description, d.amount, d.categoryId)
        val acc = d.accountId?.let { id -> accounts.getAll().firstOrNull { it.id == id } ?: error("Conto inesistente") }
        val amount = it.spese.core.common.Money.scale(d.amount)
        val key = Duplicates.key(d.accountId?.toString() ?: "", d.bookingDate, d.valueDate, amount, d.description.trim())
        if (d.id == 0L) {
            val ext = Duplicates.key(acc?.name ?: "", d.bookingDate, d.valueDate, amount, d.description.trim()) + "#m" + UUID.randomUUID().toString().take(8)
            tx.insertAll(listOf(NewTransaction(ext, key, d.accountId, d.bookingDate, d.valueDate, d.description.trim(), amount, c.kind,
                c.categoryId, c.manual, c.ruleId, d.merchant?.takeIf { it.isNotBlank() }, d.notes, d.origin, d.documentId)))
            return 0
        }
        val old = tx.get(d.id) ?: error("Movimento inesistente")
        tx.update(old.copy(accountId = d.accountId, bookingDate = d.bookingDate, valueDate = d.valueDate, description = d.description.trim(),
            amount = amount, kind = c.kind, categoryId = c.categoryId, manualCategory = c.manual, ruleId = c.ruleId,
            merchantOverride = d.merchant?.takeIf { it.isNotBlank() }, notes = d.notes, documentId = d.documentId ?: old.documentId), key)
        return d.id
    }
}

/** Duplica un movimento (con data odierna opzionale). */
class DuplicateTransactionsUseCase @Inject constructor(private val tx: TransactionRepository, private val accounts: AccountRepository) {
    suspend operator fun invoke(ids: Collection<Long>): Int {
        var n = 0
        val accs = accounts.getAll().associateBy { it.id }
        for (id in ids) {
            val t = tx.get(id) ?: continue
            val name = t.accountId?.let { id -> accs[id]?.name } ?: ""
            val key = Duplicates.key(t.accountId?.toString() ?: "", t.bookingDate, t.valueDate, t.amount, t.description)
            val ext = Duplicates.key(name, t.bookingDate, t.valueDate, t.amount, t.description) + "#m" + UUID.randomUUID().toString().take(8)
            n += tx.insertAll(listOf(NewTransaction(ext, key, t.accountId, t.bookingDate, t.valueDate, t.description, t.amount, t.kind, t.categoryId,
                t.manualCategory, t.ruleId, t.merchantOverride, t.notes, TxOrigin.MANUAL)))
        }
        return n
    }
}

/** Assegnazione manuale della categoria a uno o più movimenti (prevale sulle regole). */
class AssignCategoryUseCase @Inject constructor(private val tx: TransactionRepository, private val categories: CategoryRepository) {
    suspend operator fun invoke(txIds: Collection<Long>, categoryId: Long) {
        val kind = categories.getAll().firstOrNull { it.id == categoryId }?.kind ?: return
        tx.applyClassifications(txIds.map { ClassificationUpdate(it, categoryId, kind, true, null) })
    }
}

/**
 * Crea una regola dalla parola chiave (in cima alla lista) e riclassifica. L'eventuale assegnazione manuale del
 * movimento di origine viene rimossa, come fa la versione HTML ("Crea regola").
 */
class CreateRuleUseCase @Inject constructor(
    private val rules: RuleRepository, private val tx: TransactionRepository, private val reclassify: ReclassifyAllUseCase,
) {
    suspend operator fun invoke(keyword: String, categoryId: Long, sign: AmountSign = AmountSign.ANY,
                                min: BigDecimal = BigDecimal.ZERO, max: BigDecimal = BigDecimal.ZERO, releaseTxId: Long? = null) {
        require(keyword.isNotBlank() || min.signum() != 0 || max.signum() != 0) { "Indica una parola chiave e/o un importo." }
        rules.insertFirst(Rule(0, keyword.trim(), categoryId, sign, min, max, 0))
        if (releaseTxId != null) tx.get(releaseTxId)?.let { t ->
            if (t.manualCategory) tx.applyClassifications(listOf(ClassificationUpdate(t.id, t.categoryId, t.kind, false, null)))
        }
        reclassify()
    }
}

/** Elimina una categoria: regole e assegnazioni manuali collegate vengono rimosse, i movimenti passano a "Altro". */
class DeleteCategoryUseCase @Inject constructor(
    private val categories: CategoryRepository, private val tx: TransactionRepository, private val reclassify: ReclassifyAllUseCase,
) {
    suspend operator fun invoke(categoryId: Long) {
        val c = categories.getAll().firstOrNull { it.id == categoryId } ?: return
        require(c.systemKey == null) { "Questa categoria di sistema non può essere eliminata." }
        val affected = tx.getAll().filter { it.categoryId == categoryId && it.manualCategory }
        if (affected.isNotEmpty()) tx.applyClassifications(affected.map { ClassificationUpdate(it.id, null, it.kind, false, null) })
        categories.delete(categoryId)
        reclassify()
    }
}

/** Salva una categoria (nuova o modificata) e riclassifica se cambia la tipologia. */
class SaveCategoryUseCase @Inject constructor(private val categories: CategoryRepository, private val reclassify: ReclassifyAllUseCase) {
    suspend operator fun invoke(c: Category): Long {
        require(c.name.isNotBlank()) { "Inserisci un nome." }
        val all = categories.getAll()
        require(all.none { it.id != c.id && it.name.equals(c.name.trim(), ignoreCase = true) }) { "Esiste già una categoria con questo nome." }
        c.parentId?.let { pid ->
            val parent = all.firstOrNull { it.id == pid }
            require(parent != null && pid != c.id) { "Categoria principale non valida." }
            require(parent.parentId == null) { "Una sottocategoria non può avere altre sottocategorie." }
            require(parent.kind == c.kind) { "La sottocategoria deve essere dello stesso tipo della principale." }
            require(all.none { it.parentId == c.id }) { "Questa categoria ha già delle sottocategorie: non può diventarne una." }
        }
        val id = if (c.id == 0L) categories.insert(c.copy(name = c.name.trim(), sortOrder = all.size)) else { categories.update(c.copy(name = c.name.trim())); c.id }
        reclassify()
        return id
    }

    fun nextColor(count: Int): Long = DefaultData.PALETTE[count % DefaultData.PALETTE.size]
}

/** Conferma i giroconti proposti: entrambe le metà passano alla categoria dei trasferimenti (assegnazione manuale) e escono da entrate e spese. */
class ConfirmTransfersUseCase @Inject constructor(private val assign: AssignCategoryUseCase, private val categories: CategoryRepository) {
    suspend operator fun invoke(pairs: Collection<it.spese.domain.classification.TransferDetector.Pair>): Int {
        if (pairs.isEmpty()) return 0
        val cat = it.spese.domain.classification.TransferSuggestions.transferCategory(categories.getAll()) ?: throw IllegalStateException("Manca la categoria «Trasferimenti»: creala in Categorie.")
        assign(pairs.flatMap { listOf(it.outgoing.id, it.incoming.id) }.distinct(), cat.id)
        return pairs.size
    }
}

/**
 * Assegna un conto ai movimenti (a quelli che non lo hanno, o li sposta). La chiave di deduplica dipende dal conto e si ricalcola,
 * tranne per i provvisori (notifiche e ricorrenze), la cui chiave non dipende dal conto: così l'estratto conto li sostituisce come prima.
 */
class AssignAccountUseCase @Inject constructor(private val tx: TransactionRepository, private val accounts: AccountRepository) {
    suspend operator fun invoke(txIds: Collection<Long>, accountId: Long?): Int {
        if (accountId != null) accounts.getAll().firstOrNull { it.id == accountId } ?: error("Conto inesistente")
        var n = 0
        for (id in txIds) {
            val t = tx.get(id) ?: continue
            if (t.accountId == accountId) continue
            val provisional = t.origin == TxOrigin.NOTIFICATION || t.origin == TxOrigin.RECURRING
            val key = if (provisional) tx.getDedupKey(id) ?: t.externalId else Duplicates.key(accountId?.toString() ?: "", t.bookingDate, t.valueDate, t.amount, t.description.trim())
            tx.update(t.copy(accountId = accountId), key)
            n++
        }
        return n
    }
}
