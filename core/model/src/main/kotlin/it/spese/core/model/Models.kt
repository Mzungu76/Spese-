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

/** Natura di una spesa: quanto si può incidere su di essa. */
enum class SpendNature(val key: String, val label: String, val hint: String) {
    FIXED("f", "Fissa", "Importo ricorrente che non cambia da un mese all'altro (affitto, rate, abbonamenti)"),
    ESSENTIAL("e", "Necessaria", "Indispensabile ma variabile (spesa alimentare, salute, carburante)"),
    DISCRETIONARY("d", "Discrezionale", "Di cui si può fare a meno o ridurre (ristoranti, shopping, svago)");

    companion object {
        fun fromKey(k: String?): SpendNature? = entries.firstOrNull { it.key == k }
    }
}

/** Origine di un movimento. NOTIFICATION e RECURRING = provvisori (da notifica o SMS, o da un movimento ricorrente): l'estratto conto li sostituisce all'importazione. */
enum class TxOrigin { MANUAL, IMPORT_FILE, BACKUP, OCR, NOTIFICATION, RECURRING }

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
    /** Categoria di cui questa è sottocategoria (un solo livello, stesso tipo); null = categoria principale. */
    val parentId: Long? = null,
    /** Natura della spesa (fissa / necessaria / discrezionale); null = non ancora definita. */
    val nature: SpendNature? = null,
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
    /** true = l'utente ha controllato il movimento: esce dalla sezione Verifica. */
    val reviewed: Boolean = false,
)

/**
 * Parte di un movimento diviso su più categorie (es. un prelievo da 800 € = 500 € Affitto + 300 € Contanti).
 * `amount` ha lo stesso segno del movimento; la somma delle parti è l'importo del movimento.
 */
data class TransactionSplit(
    val id: Long, val txId: Long, val categoryId: Long?, val amount: BigDecimal, val position: Int = 0,
    /** true = creata automaticamente da una quota fissa. */
    val auto: Boolean = false, val quotaId: Long? = null,
)

/** Quando una quota fissa in contanti si applica a un prelievo. */
enum class QuotaMode(val key: String, val label: String, val hint: String) {
    COVERING("c", "Primo prelievo che la copre", "Il primo prelievo del periodo di importo pari o superiore alla quota"),
    EVERY("e", "Ogni prelievo che la copre", "Ogni prelievo di importo pari o superiore alla quota"),
    EXACT("x", "Solo se l'importo coincide", "Solo se il prelievo è la quota (con tolleranza): niente resto");

    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: COVERING }
}

/**
 * Quota fissa pagata in contanti (es. affitto 500 €): quando un prelievo la contiene, la quota viene staccata
 * e assegnata alla categoria indicata, il resto resta com'è. Si può applicare ogni mese o ogni N mesi, entro una finestra di giorni,
 * e più quote possono convivere nello stesso prelievo.
 */
data class CashQuota(
    val id: Long, val name: String, val categoryId: Long, val amount: BigDecimal, val enabled: Boolean = true,
    val mode: QuotaMode = QuotaMode.COVERING,
    /** Solo per [QuotaMode.EXACT]: scostamento ammesso dall'importo. */
    val tolerance: BigDecimal = BigDecimal.ZERO,
    /** 1 = ogni mese, 3 = ogni trimestre, 12 = ogni anno: la quota si applica una volta per periodo (non per [QuotaMode.EVERY]). */
    val everyMonths: Int = 1,
    /** Finestra di giorni del mese in cui cercare il prelievo (0 = nessun limite). */
    val dayFrom: Int = 0, val dayTo: Int = 0,
)

/**
 * Entrata o spesa che si ripete (stipendio, affitto incassato, abbonamento…): l'app crea da sola il movimento alla data giusta,
 * come provvisorio da controllare in Verifica (oppure già confermato, se `autoConfirm`).
 */
data class RecurringRule(
    val id: Long, val name: String, val kind: TxKind, val amount: BigDecimal,
    /** Conto su cui registrare (null = il primo conto). */
    val accountId: Long? = null,
    /** Categoria fissa (null = la scelgono le regole di classificazione). */
    val categoryId: Long? = null,
    /** 1 = ogni mese, 2, 3, 6, 12 = ogni anno. */
    val everyMonths: Int = 1,
    /** Giorno del mese (se il mese è più corto, l'ultimo giorno). */
    val dayOfMonth: Int = 1,
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val enabled: Boolean = true,
    /** true = il movimento nasce già controllato (non passa da Verifica). */
    val autoConfirm: Boolean = false,
    /** Ultima data per cui è stato creato un movimento (null = nessuno ancora). */
    val lastGenerated: LocalDate? = null,
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

/**
 * Obiettivo di risparmio (fondo di emergenza, vacanza, investimento…). `saved` è quanto è già stato messo da parte;
 * la quota mensile è quella scelta (`monthly`) oppure, se manca, quella che serve per arrivare alla scadenza.
 */
data class Goal(
    val id: Long,
    val name: String,
    val target: BigDecimal,
    val saved: BigDecimal = BigDecimal.ZERO,
    val deadline: LocalDate? = null,
    /** Quota mensile scelta; null = calcolata dalla scadenza. */
    val monthly: BigDecimal? = null,
    /** 0 = la più importante. */
    val priority: Int = 0,
    val enabled: Boolean = true,
)

/** Valore di un conto di investimento a una data (inserito dall'utente): da lì in poi si aggiungono i movimenti successivi. */
data class Valuation(val accountId: Long, val date: LocalDate, val value: BigDecimal)
