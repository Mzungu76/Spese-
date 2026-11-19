package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Rule
import it.spese.core.model.Transaction
import it.spese.core.model.TransactionSplit
import it.spese.core.model.TxKind
import it.spese.core.model.TxOrigin
import java.math.BigDecimal
import java.math.RoundingMode

enum class ReviewReason { NO_ACCOUNT, AUTO_SPLIT, PROVISIONAL, RECURRING, THRESHOLD_RULE, UNUSUAL, INCOME_MONTH }

data class ReviewNote(val reason: ReviewReason, val text: String)
data class ReviewItem(val tx: Transaction, val notes: List<ReviewNote>, val splits: List<TransactionSplit>)

/**
 * Sezione "Verifica" dei Movimenti: i movimenti la cui associazione non è certa.
 * - senza conto: restano fuori da saldi, entrate e spese finché non si sceglie il conto (non si possono «confermare»);
 * - divisi automaticamente da una quota fissa (prelievi);
 * - provvisori nati da notifiche, in attesa dell'estratto conto;
 * - assegnati da una regola con soglia di importo;
 * - con importo insolito per la loro categoria (oltre 3 deviazioni standard dalla media storica).
 * Gli ultimi due si guardano solo sui movimenti recenti (60 giorni dall'ultimo movimento), per non riempire l'elenco con lo storico.
 * Un movimento controllato (`reviewed`) o assegnato a mano dall'utente non compare.
 */
object ReviewEngine {
    const val RECENT_DAYS = 60L

    fun items(ledger: List<Transaction>, splits: Map<Long, List<TransactionSplit>>, categories: List<Category>, rules: List<Rule>, unassigned: List<Transaction> = emptyList(), calendar: it.spese.core.common.MonthCalendar = it.spese.core.common.MonthCalendar.SOLAR, askDays: Int = 0): List<ReviewItem> {
        if (ledger.isEmpty() && unassigned.isEmpty()) return emptyList()
        val names = categories.associate { it.id to it.name }
        val rulesById = rules.associateBy { it.id }
        val recentFrom = (ledger + unassigned).maxOf { it.bookingDate }.minusDays(RECENT_DAYS)
        val history = ledger.filter { it.kind == TxKind.SPESA && splits[it.id].isNullOrEmpty() }.groupBy { it.categoryId }
        val out = ArrayList<ReviewItem>()
        for (t in unassigned + ledger) {
            val noAccount = t.accountId == null
            if (t.reviewed && !noAccount) continue       // «controllato» non basta: senza conto resta in Verifica
            val notes = ArrayList<ReviewNote>()
            if (noAccount) notes.add(ReviewNote(ReviewReason.NO_ACCOUNT, "Senza conto: resta fuori da saldi, entrate e spese finché non scegli il conto"))
            val parts = splits[t.id].orEmpty().sortedBy { it.position }
            if (parts.any { it.auto }) notes.add(ReviewNote(ReviewReason.AUTO_SPLIT, "Diviso automaticamente: " + parts.joinToString(" + ") { (names[it.categoryId] ?: "Senza categoria") + " " + Money.eur0(it.amount.abs()) }))
            if (t.origin == TxOrigin.RECURRING) notes.add(ReviewNote(ReviewReason.RECURRING, "Ricorrente: creato in automatico, confermalo quando arriva davvero (l'estratto conto lo sostituisce)"))
            if (t.origin == TxOrigin.OCR && t.documentId != null) notes.add(ReviewNote(ReviewReason.PROVISIONAL, "Da scontrino: attende la riga dell'estratto conto che lo sostituisce (se l'hai pagato in contanti, confermalo)"))
            if (t.origin == TxOrigin.NOTIFICATION) notes.add(ReviewNote(ReviewReason.PROVISIONAL, "Da notifica: sarà sostituito dall'estratto conto"))
            if (!noAccount && !t.manualCategory && parts.isEmpty() && t.bookingDate >= recentFrom) {
                val rule = t.ruleId?.let { rulesById[it] }
                if (rule != null && (rule.min.signum() != 0 || rule.max.signum() != 0)) {
                    val limit = buildString { if (rule.min.signum() != 0) append("da ").append(Money.eur0(rule.min)); if (rule.max.signum() != 0) { if (isNotEmpty()) append(" "); append("fino a ").append(Money.eur0(rule.max)) } }
                    notes.add(ReviewNote(ReviewReason.THRESHOLD_RULE, "Assegnato da una regola con soglia di importo ($limit)"))
                }
                unusual(t, history[t.categoryId].orEmpty())?.let { notes.add(it) }
            }
            if (askDays > 0 && t.bookingDate >= recentFrom && AccountingMonth.needsChoice(t, calendar, askDays)) {
                val cur = calendar.monthOf(t.bookingDate)
                notes.add(ReviewNote(ReviewReason.INCOME_MONTH, "Entrata a fine mese contabile (finisce il ${it.spese.core.common.Dates.fmt(calendar.end(cur)).take(5)}): scegli se contarla per ${it.spese.core.common.Dates.monthLabelLong(cur)} o per il mese dopo"))
            }
            if (notes.isNotEmpty()) out.add(ReviewItem(t, notes, parts))
        }
        return out.sortedWith(compareByDescending<ReviewItem> { it.tx.bookingDate }.thenByDescending { it.tx.id })
    }

    private fun unusual(t: Transaction, hist: List<Transaction>): ReviewNote? {
        if (t.kind != TxKind.SPESA) return null
        val a = t.amount.negate()
        if (a < BigDecimal(50)) return null
        val before = hist.filter { it.bookingDate < t.bookingDate }.map { it.amount.negate() }
        if (before.size < 5) return null
        val mean = before.fold(BigDecimal.ZERO) { s, v -> s + v }.divide(BigDecimal(before.size), 10, RoundingMode.HALF_UP)
        val variance = before.fold(BigDecimal.ZERO) { s, v -> s + (v - mean).pow(2) }.divide(BigDecimal(before.size), 10, RoundingMode.HALF_UP)
        return if (a > mean && (a - mean).pow(2) > variance.multiply(BigDecimal(9))) ReviewNote(ReviewReason.UNUSUAL, "Importo insolito: ${Money.eur0(a)} contro una media di ${Money.eur0(mean)} per questa categoria") else null
    }
}
