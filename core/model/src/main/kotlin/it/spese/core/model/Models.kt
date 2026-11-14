package it.spese.core.model

import java.math.BigDecimal
import java.time.LocalDate

/** Tipologia di movimento. `key` coincide con i valori usati dalla versione HTML ("entrata", "spesa", "trasferimento"). */
enum class TxKind(val key: String, val label: String) {
    ENTRATA("entrata", "Entrata"),
    SPESA("spesa", "Spesa"),
    TRASFERIMENTO("trasferimento", "Trasferimento");

    companion object {
        fun fromKey(k: String?): TxKind? = entries.firstOrNull { it.key == k }
    }
}

/** Segno su cui una regola di classificazione è valida: '' (qualsiasi), '-' (uscite), '+' (entrate). */
enum class AmountSign(val key: String) {
    ANY(""), NEGATIVE("-"), POSITIVE("+");

    companion object {
        fun fromKey(k: String?): AmountSign = entries.firstOrNull { it.key == (k ?: "") } ?: ANY
    }
}

enum class DueFrequency(val key: String, val label: String) {
    MONTHLY("m", "ogni mese"),
    YEARLY("a", "ogni anno"),
    ONCE("o", "una volta");

    companion object {
        fun fromKey(k: String?): DueFrequency = entries.firstOrNull { it.key == k } ?: MONTHLY
    }
}

enum class TxOrigin { MANUAL, IMPORT_FILE, BACKUP, OCR }

enum class AccountType(val label: String) {
    CONTO("Conto corrente"), CARTA("Carta"), CONTANTI("Contanti"), INVESTIMENTO("Investimenti"), ALTRO("Altro")
}

enum class GoalMode(val key: String) {
    MONTHLY("m"), TOTAL("t");

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: MONTHLY
    }
}

data class Account(
    val id: Long,
    val name: String,
    val type: AccountType = AccountType.CONTO,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val archived: Boolean = false,
)

data class Category(
    val id: Long,
    val name: String,
    val kind: TxKind,
    /** "Comprimibile": voce su cui il simulatore può proporre tagli. */
    val flexible: Boolean,
    /** Colore ARGB (0xFFRRGGBB). */
    val color: Long,
    /** Budget mensile opzionale. */
    val budgetMonthly: BigDecimal? = null,
    val active: Boolean = true,
    val sortOrder: Int = 0,
    /** Categoria di sistema usata come fallback ("FALLBACK_EXPENSE" / "FALLBACK_INCOME"): non eliminabile. */
    val systemKey: String? = null,
    /** Chiave dell'icona scelta (vedi [CategoryIcons]); null = icona di default dedotta dal nome. */
    val icon: String? = null,
) {
    /** Icona effettiva: quella scelta, se ancora nel catalogo, altrimenti il default. */
    val iconKey: String get() = CategoryIcons.resolve(this)

    companion object {
        const val FALLBACK_EXPENSE = "FALLBACK_EXPENSE"
        const val FALLBACK_INCOME = "FALLBACK_INCOME"
    }
}

data class Rule(
    val id: Long,
    /** Parola chiave: testo semplice = "contiene"; prefisso `re:` = espressione regolare. Può essere vuota (solo importo). */
    val keyword: String,
    val categoryId: Long,
    val sign: AmountSign = AmountSign.ANY,
    /** Importo minimo in valore assoluto (0 = nessun limite). */
    val min: BigDecimal = BigDecimal.ZERO,
    /** Importo massimo in valore assoluto (0 = nessun limite). */
    val max: BigDecimal = BigDecimal.ZERO,
    /** Posizione nella lista: vince la prima regola che corrisponde. */
    val priority: Int = 0,
    val active: Boolean = true,
)

data class Transaction(
    val id: Long,
    /** Identificativo stabile compatibile con la versione HTML (`conto|data|valuta|importo|descrizione#n`). */
    val externalId: String,
    val accountId: Long,
    val bookingDate: LocalDate,
    val valueDate: LocalDate,
    val description: String,
    val amount: BigDecimal,
    val kind: TxKind,
    val categoryId: Long?,
    /** true se la categoria è stata assegnata manualmente (prevale sulle regole). */
    val manualCategory: Boolean = false,
    /** Regola che ha classificato il movimento (0/null = nessuna). */
    val ruleId: Long? = null,
    val merchantOverride: String? = null,
    val notes: String = "",
    val origin: TxOrigin = TxOrigin.MANUAL,
    val documentId: Long? = null,
    val createdAt: Long = 0,
)

data class Due(
    val id: Long,
    val legacyId: String,
    val name: String,
    val amount: BigDecimal,
    val next: LocalDate,
    val frequency: DueFrequency,
)

data class SavingsGoal(
    val mode: GoalMode = GoalMode.MONTHLY,
    val value: BigDecimal = BigDecimal(300),
    val months: Int = 12,
    /** Entrate mensili attese; null = media del periodo. */
    val expectedIncome: BigDecimal? = null,
)

data class Scenario(
    val id: Long,
    val name: String,
    val goal: SavingsGoal = SavingsGoal(),
    /** Percentuale di taglio per categoria (id categoria -> %). */
    val cuts: Map<Long, Int> = emptyMap(),
    /** Variazione delle entrate mensili (es. +200). */
    val incomeDelta: BigDecimal = BigDecimal.ZERO,
    /** Chiavi delle ricorrenze/scadenze escluse dalla simulazione. */
    val removedRecurring: Set<String> = emptySet(),
    val isBase: Boolean = false,
)

data class DocumentRef(
    val id: Long,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val ocrText: String? = null,
)
