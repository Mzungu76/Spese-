package it.spese.domain.finance

import it.spese.core.common.MonthCalendar
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class PaceUnit(val label: String, val article: String) { WEEK("settimana", "la"), MONTH("mese", "il"), QUARTER("trimestre", "il") }

/** Su cosa poggia il limite di spesa ottimale. */
enum class OptimalBasis(val label: String) {
    NONE(""), CUSTOM("budget fissato da te"), GOALS("entrate recenti meno la quota dei tuoi obiettivi"), RATE("entrate recenti (nessun obiettivo da risparmiare)"), BUDGETS("budget delle categorie scelte"),
    /** Il budget giornaliero dell'app: entrate prudenti, obiettivi, margine di sicurezza. */
    BUDGET("entrate prudenti, obiettivi e margine di sicurezza")
}

enum class PaceVerdict(val label: String) {
    IN_LINE("In linea con la media"), ABOVE("Sopra la media"), BELOW("Sotto la media"), NOT_ENOUGH("Storico insufficiente")
}

/** Un periodo passato dello stesso tipo (settimana, mese, trimestre) con la spesa totale e le entrate. */
data class PaceWindow(val label: String, val start: LocalDate, val total: BigDecimal, val income: BigDecimal)

/** Una categoria: quanto speso finora nel periodo in corso e quanto in media nello stesso punto dei periodi passati. */
data class PaceDriver(val category: Category?, val current: BigDecimal, val average: BigDecimal) { val delta: BigDecimal get() = current - average }

/**
 * Avanzamento delle spese del periodo in corso (settimana, mese o trimestre secondo il filtro) confrontato con l'andamento medio
 * di tutti i periodi passati dello stesso tipo, giorno per giorno. Risponde a: «a questo punto, sto spendendo più o meno del solito?
 * e se continuo così, come chiudo il periodo rispetto alle entrate?»
 */
data class PaceReport(
    val unit: PaceUnit, val start: LocalDate, val end: LocalDate, val today: LocalDate,
    /** Giorni totali del periodo e indice (da 0) di oggi. */
    val days: Int, val dayIndex: Int,
    /** Spesa cumulata del periodo in corso, un valore per giorno fino a oggi. */
    val current: List<BigDecimal>,
    /** Spesa cumulata media e fascia (minimo/massimo) dei periodi passati, un valore per giorno dell'intero periodo. */
    val average: List<BigDecimal>, val low: List<BigDecimal>, val high: List<BigDecimal>,
    val pastWindows: List<PaceWindow>,
    val spentToDate: BigDecimal, val averageToDate: BigDecimal, val averageTotal: BigDecimal,
    /** Chiusura stimata: se il resto del periodo va come al solito / se si continua al ritmo attuale. */
    val projectedWithAverage: BigDecimal, val projectedAtCurrentPace: BigDecimal,
    val averageIncome: BigDecimal, val incomeToDate: BigDecimal,
    val drivers: List<PaceDriver>, val verdict: PaceVerdict,
    /** Categorie scelte in Home: se non è vuoto il ritmo riguarda solo la spesa di queste (con le loro sottocategorie) e le entrate non c'entrano. */
    val filtered: List<Category> = emptyList(),
    /**
     * Spesa ottimale: la curva cumulata che rispetta il limite di spesa del periodo (entrate recenti − risparmio da mettere da parte,
     * oppure la somma dei budget delle categorie scelte), distribuita come di solito si spende. Vuota se non calcolabile.
     */
    val optimal: List<BigDecimal> = emptyList(),
    /** Il limite di spesa dell'intero periodo (null se non calcolabile). */
    val optimalTotal: BigDecimal? = null,
    /** Entrate medie recenti e risparmio del periodo su cui si basa il limite (0 se il ritmo è filtrato per categoria). */
    val recentIncome: BigDecimal = BigDecimal.ZERO, val savingsTarget: BigDecimal = BigDecimal.ZERO,
    val optimalBasis: OptimalBasis = OptimalBasis.NONE,
) {
    /** Quanto sarebbe «ottimale» aver speso a questo punto del periodo. */
    val optimalToDate: BigDecimal? get() = optimal.getOrNull(dayIndex)
    /** Quanto si può spendere al giorno da qui a fine periodo per restare nel limite (negativo = limite già superato). */
    val optimalDailyRemaining: BigDecimal? get() = if (optimalTotal == null || remainingDays <= 0) null else (optimalTotal - spentToDate).divide(BigDecimal(remainingDays), 2, RoundingMode.HALF_UP)
    /** Il limite diviso per i giorni del periodo: la media giornaliera «ideale». */
    val optimalDailyAverage: BigDecimal? get() = optimalTotal?.divide(BigDecimal(days), 2, RoundingMode.HALF_UP)
    val deltaToDate: BigDecimal get() = spentToDate - averageToDate
    val deltaPercent: BigDecimal? get() = if (averageToDate.signum() <= 0) null else deltaToDate.multiply(BigDecimal(100)).divide(averageToDate, 0, RoundingMode.HALF_UP)
    val remainingDays: Int get() = days - dayIndex - 1
    /** Entrate attese nel periodo: la media storica, ma mai meno di quanto già incassato. */
    val expectedIncome: BigDecimal get() = averageIncome.max(incomeToDate)
    /** Margine atteso a fine periodo (entrate attese − spesa stimata come al solito) e margine medio storico. */
    val expectedMargin: BigDecimal get() = expectedIncome - projectedWithAverage
    val averageMargin: BigDecimal get() = averageIncome - averageTotal
    /** Quanto si può spendere al giorno da qui a fine periodo per chiudere col risparmio medio storico (può essere negativo). */
    val dailyAllowance: BigDecimal? get() = if (remainingDays <= 0) null else
        (expectedIncome - averageMargin.max(BigDecimal.ZERO) - spentToDate).divide(BigDecimal(remainingDays), 2, RoundingMode.HALF_UP)
    val averageDailyRemaining: BigDecimal? get() = if (remainingDays <= 0) null else (averageTotal - averageToDate).divide(BigDecimal(remainingDays), 2, RoundingMode.HALF_UP)
}

object PaceEngine {
    private const val TOLERANCE = 0.07
    private val MON = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)

    private class Win(val start: LocalDate, val end: LocalDate) { val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt() }

    fun unitFor(period: Period): PaceUnit = when (period) {
        Period.CurrentWeek -> PaceUnit.WEEK
        Period.Last3Months -> PaceUnit.QUARTER
        else -> PaceUnit.MONTH
    }

    fun compute(data: AppData, filter: TxFilter, realToday: LocalDate = LocalDate.now(), categoryIds: Set<Long> = emptySet(), savingsMonthly: BigDecimal? = null, budgetMonthly: BigDecimal? = null, budgetIsComputed: Boolean = false): PaceReport? {
        val ids0 = categoryIds.flatMapTo(HashSet()) { CategoryTree.withChildren(it, data.categories) }
        val tx = data.transactions.filter { (filter.accountId == null || it.accountId == filter.accountId) && it.kind != TxKind.TRASFERIMENTO }
            .filter { categoryIds.isEmpty() || (it.kind == TxKind.SPESA && it.categoryId in ids0) }
        val chosen = data.categories.filter { it.id in categoryIds }
        if (tx.isEmpty()) return null
        val cal = filter.calendar
        val unit = unitFor(filter.period)
        val dataMin = tx.minOf { it.bookingDate }
        val last = tx.maxOf { it.bookingDate }
        var today = AlertEngine.referenceToday(realToday, tx)
        // come i filtri «Sett./Mese»: se nel periodo di oggi non c'è ancora nessun movimento, il periodo in corso è quello dell'ultimo movimento
        val currentStart = when (unit) {
            PaceUnit.WEEK -> today.with(MON)
            PaceUnit.MONTH -> cal.start(cal.monthOf(today))
            PaceUnit.QUARTER -> cal.start(cal.monthOf(today).minusMonths(2))
        }
        if (last.isBefore(currentStart) && unit != PaceUnit.QUARTER) today = last
        val ym = cal.monthOf(today)

        val current: Win; val past: List<Win>
        when (unit) {
            PaceUnit.WEEK -> {
                val mon = today.with(MON)
                current = Win(mon, mon.plusDays(6))
                past = (1..104).map { k -> Win(mon.minusWeeks(k.toLong()), mon.minusWeeks(k.toLong()).plusDays(6)) }
            }
            PaceUnit.MONTH -> {
                val m = (filter.period as? Period.Month)?.month ?: ym
                current = Win(cal.start(m), cal.end(m))
                past = (1..48).map { k -> Win(cal.start(m.minusMonths(k.toLong())), cal.end(m.minusMonths(k.toLong()))) }
            }
            PaceUnit.QUARTER -> {
                current = Win(cal.start(ym.minusMonths(2)), cal.end(ym))
                past = (1..16).map { k -> Win(cal.start(ym.minusMonths(2 + 3L * k)), cal.end(ym.minusMonths(3L * k))) }
            }
        }
        // solo periodi passati interamente coperti dai dati (il primo, parziale, falserebbe la media)
        val windows = past.filter { !it.start.isBefore(dataMin) }
        val dayIndex = ChronoUnit.DAYS.between(current.start, minOf(today, current.end)).toInt().coerceIn(0, current.days - 1)

        fun daily(w: Win, upTo: Int): Pair<List<BigDecimal>, BigDecimal> {
            val inWin = tx.filter { it.bookingDate >= w.start && it.bookingDate <= w.end }
            var income = BigDecimal.ZERO
            val spendBd = Array<BigDecimal>(w.days) { BigDecimal.ZERO }
            inWin.forEach { t ->
                val i = ChronoUnit.DAYS.between(w.start, t.bookingDate).toInt()
                if (t.kind == TxKind.SPESA) spendBd[i] = spendBd[i] + t.amount.negate() else if (t.kind == TxKind.ENTRATA) income += t.amount
            }
            var acc = BigDecimal.ZERO
            val cum = (0 until minOf(upTo + 1, w.days)).map { i -> acc += spendBd[i]; acc }
            return cum to income
        }

        val (cur, curIncome) = daily(current, dayIndex)
        val pastData = windows.map { w -> daily(w, w.days - 1).let { (c, inc) -> Triple(w, c, inc) } }
        val perUnit = when (unit) { PaceUnit.WEEK -> BigDecimal(12).divide(BigDecimal(52), 10, RoundingMode.HALF_UP); PaceUnit.MONTH -> BigDecimal.ONE; PaceUnit.QUARTER -> BigDecimal(3) }
        // un budget fissato a mano vale anche senza storico: la curva è una retta
        val custom = budgetMonthly?.takeIf { it.signum() > 0 }?.multiply(perUnit)?.setScale(2, RoundingMode.HALF_UP)
        if (pastData.isEmpty()) {
            val line = custom?.let { t -> (0 until current.days).map { i -> t.multiply(BigDecimal(i + 1)).divide(BigDecimal(current.days), 2, RoundingMode.HALF_UP) } } ?: emptyList()
            return PaceReport(unit, current.start, current.end, today, current.days, dayIndex, cur, emptyList(), emptyList(), emptyList(), emptyList(), cur.last(), BigDecimal.ZERO, BigDecimal.ZERO,
                cur.last(), projectLinear(cur.last(), dayIndex, current.days), BigDecimal.ZERO, curIncome, emptyList(), PaceVerdict.NOT_ENOUGH, chosen,
                line, custom, BigDecimal.ZERO, BigDecimal.ZERO, if (custom != null) (if (budgetIsComputed) OptimalBasis.BUDGET else OptimalBasis.CUSTOM) else OptimalBasis.NONE)
        }
        // curve medie/minime/massime giorno per giorno (un periodo più corto resta al suo totale)
        fun valueAt(c: List<BigDecimal>, i: Int) = c.getOrElse(i) { c.last() }
        val avg = (0 until current.days).map { i -> pastData.fold(BigDecimal.ZERO) { a, p -> a + valueAt(p.second, i) }.divide(BigDecimal(pastData.size), 2, RoundingMode.HALF_UP) }
        val low = (0 until current.days).map { i -> pastData.minOf { valueAt(it.second, i) } }
        val high = (0 until current.days).map { i -> pastData.maxOf { valueAt(it.second, i) } }
        val avgToDate = avg[dayIndex]; val avgTotal = avg.last(); val spent = cur.last()
        val avgIncome = pastData.fold(BigDecimal.ZERO) { a, p -> a + p.third }.divide(BigDecimal(pastData.size), 2, RoundingMode.HALF_UP)

        // categorie: speso finora contro media allo stesso punto, accorpando le sottocategorie nella principale
        val byId = data.categoriesById
        fun spentByCat(w: Win, upToIdx: Int): Map<Long?, BigDecimal> = tx.filter { it.kind == TxKind.SPESA && it.bookingDate >= w.start && it.bookingDate <= minOf(w.end, w.start.plusDays(upToIdx.toLong())) }
            .groupBy { CategoryTree.rootId(it.categoryId, byId) }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() } }
        val curCats = spentByCat(current, dayIndex)
        val pastCats = pastData.map { spentByCat(it.first, dayIndex) }
        val ids = (curCats.keys + pastCats.flatMap { it.keys }).toSet()
        val drivers = ids.map { id ->
            PaceDriver(byId[id], curCats[id] ?: BigDecimal.ZERO, pastCats.fold(BigDecimal.ZERO) { a, m -> a + (m[id] ?: BigDecimal.ZERO) }.divide(BigDecimal(pastCats.size), 2, RoundingMode.HALF_UP))
        }.filter { (it.current - it.average).abs() >= BigDecimal("1") }.sortedByDescending { it.delta.abs() }.take(8)

        val verdict = when {
            pastData.size < 2 -> PaceVerdict.NOT_ENOUGH
            avgToDate.signum() <= 0 -> if (spent.signum() > 0) PaceVerdict.ABOVE else PaceVerdict.IN_LINE
            else -> { val d = (spent - avgToDate).toDouble() / avgToDate.toDouble(); if (d > TOLERANCE) PaceVerdict.ABOVE else if (d < -TOLERANCE) PaceVerdict.BELOW else PaceVerdict.IN_LINE }
        }
        // limite di spesa ottimale: entrate recenti − risparmio (obiettivi, altrimenti il 20%), oppure i budget delle categorie scelte
        val recentN = when (unit) { PaceUnit.WEEK -> 8; PaceUnit.MONTH -> 3; PaceUnit.QUARTER -> 2 }
        val recent = pastData.take(recentN)
        val recentIncome = if (chosen.isNotEmpty() || recent.isEmpty()) BigDecimal.ZERO else recent.fold(BigDecimal.ZERO) { a, p -> a + p.third }.divide(BigDecimal(recent.size), 2, RoundingMode.HALF_UP)
        var basis = OptimalBasis.NONE; var optTotal: BigDecimal? = null; var saveTarget = BigDecimal.ZERO
        if (custom != null) { optTotal = custom; basis = if (budgetIsComputed) OptimalBasis.BUDGET else OptimalBasis.CUSTOM }
        else if (chosen.isNotEmpty()) {
            val budgets = chosen.mapNotNull { it.budgetMonthly }.fold(BigDecimal.ZERO) { a, b -> a + b }
            if (budgets.signum() > 0) { optTotal = budgets.multiply(perUnit).setScale(2, RoundingMode.HALF_UP); basis = OptimalBasis.BUDGETS }
        } else if (recentIncome.signum() > 0) {
            val goalsShare = savingsMonthly?.takeIf { it.signum() > 0 }?.multiply(perUnit)
            // senza obiettivi non si inventa nessun risparmio: la quota è zero
            saveTarget = (goalsShare ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
            basis = if (goalsShare != null) OptimalBasis.GOALS else OptimalBasis.RATE
            optTotal = (recentIncome - saveTarget).max(BigDecimal.ZERO)
        }
        // la curva segue la forma di quella media (affitto il 1°, ecc.), scalata sul limite; senza forma storica è una retta
        val optimal = optTotal?.let { t ->
            (0 until current.days).map { i ->
                if (avgTotal.signum() > 0) avg[i].multiply(t).divide(avgTotal, 2, RoundingMode.HALF_UP)
                else t.multiply(BigDecimal(i + 1)).divide(BigDecimal(current.days), 2, RoundingMode.HALF_UP)
            }
        } ?: emptyList()
        return PaceReport(unit, current.start, current.end, today, current.days, dayIndex, cur, avg, low, high,
            pastData.map { (w, c, inc) -> PaceWindow(label(unit, w.start, cal), w.start, c.last(), inc) }.reversed(),
            spent, avgToDate, avgTotal, spent + (avgTotal - avgToDate), projectLinear(spent, dayIndex, current.days), avgIncome, curIncome, drivers, verdict, chosen,
            optimal, optTotal, recentIncome, saveTarget, basis)
    }

    private fun projectLinear(spent: BigDecimal, dayIndex: Int, days: Int): BigDecimal = spent.multiply(BigDecimal(days)).divide(BigDecimal(dayIndex + 1), 2, RoundingMode.HALF_UP)

    private fun label(unit: PaceUnit, start: LocalDate, cal: MonthCalendar): String = when (unit) {
        PaceUnit.WEEK -> it.spese.core.common.Dates.fmt(start).take(5)
        PaceUnit.MONTH -> it.spese.core.common.Dates.monthLabel(cal.monthOf(start)).substringBefore(' ')
        PaceUnit.QUARTER -> it.spese.core.common.Dates.monthLabel(cal.monthOf(start)).substringBefore(' ') + "+"
    }
}
