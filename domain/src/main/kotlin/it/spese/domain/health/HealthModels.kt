package it.spese.domain.health

import it.spese.core.model.Category
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Natura di una fonte di entrata: fissa (stipendio, pensione, affitto incassato) o variabile (collaborazioni, premi, rimborsi). */
enum class IncomeNature { FIXED, VARIABLE }

/** Classe di una spesa: fissa (affitto, rate), necessaria variabile (spesa, carburante), discrezionale (svago), o non classificata. */
enum class ExpenseClass(val label: String) { FIXED("Fisse"), NECESSARY("Necessarie variabili"), DISCRETIONARY("Discrezionali"), UNCLASSIFIED("Da classificare") }

enum class Level { GOOD, WARN, BAD, NA }

enum class Verdict(val label: String) { HEALTHY("In salute"), ATTENTION("Attenzione"), FRAGILE("Fragile"), DIFFICULTY("In difficoltà") }

/** Una fonte di entrata con la sua mediana mensile e come è stata classificata. */
data class IncomeSource(
    val categoryId: Long?, val name: String, val monthlyMedian: BigDecimal, val monthsPresent: Int,
    val nature: IncomeNature, val detected: IncomeNature, val overridden: Boolean,
)

/** Il flusso mensile di una categoria principale di spesa nel periodo analizzato. */
data class CategoryFlow(
    val category: Category?, val cls: ExpenseClass,
    /** true = la natura non l'ha scelta l'utente: l'ho dedotta dalla regolarità degli importi. */
    val inferred: Boolean,
    val median: BigDecimal, val p25: BigDecimal, val recent: BigDecimal, val previous: BigDecimal, val series: List<BigDecimal>,
) { val name: String get() = category?.name ?: "Senza categoria" }

/** Un indicatore del parere: valore, semaforo, punteggio 0–100 (null = non calcolabile), peso e spiegazione. */
data class Indicator(val id: String, val label: String, val value: String, val level: Level, val score: Int?, val weight: Int, val explanation: String)

/** Un intervento proposto su una categoria: dal livello tipico (`current`) a quello che l'utente ha già mantenuto nei mesi sobri (`target`). */
data class Lever(
    val category: Category, val cls: ExpenseClass, val current: BigDecimal, val target: BigDecimal, val saving: BigDecimal,
    val growing: Boolean, val why: String,
    /** Budget mensile suggerito (arrotondato a 5 € per eccesso). Si imposta solo se l'utente lo chiede. */
    val budgetSuggestion: BigDecimal,
)

/** Una voce fissa da tenere d'occhio (si rivede, non si taglia a forza). */
data class WatchItem(val title: String, val text: String, val monthlyImpact: BigDecimal?)

data class HealthReport(
    val asOf: LocalDate, val months: List<YearMonth>, val provisional: Boolean,
    val score: Int, val verdict: Verdict,
    // entrate
    val incomeFixed: BigDecimal, val incomeVariableTypical: BigDecimal, val incomeVariablePrudent: BigDecimal,
    val typicalIncome: BigDecimal, val prudentIncome: BigDecimal, val incomeSources: List<IncomeSource>,
    // spese (mediane mensili per classe)
    val expenseFixed: BigDecimal, val expenseNecessary: BigDecimal, val expenseDiscretionary: BigDecimal, val expenseUnclassified: BigDecimal,
    val expenseFlows: List<CategoryFlow>, val classifiedPercent: Int,
    // risparmio
    val typicalSaving: BigDecimal, val savingRatePercent: Int?, val prudentSaving: BigDecimal, val targetSaving: BigDecimal, val gap: BigDecimal,
    val indicators: List<Indicator>, val levers: List<Lever>, val leversSaving: BigDecimal, val residualGap: BigDecimal,
    val watch: List<WatchItem>, val notes: List<String>, val headline: String,
) {
    val monthCount: Int get() = months.size
    val expenseTypical: BigDecimal get() = expenseFixed + expenseNecessary + expenseDiscretionary + expenseUnclassified
}
