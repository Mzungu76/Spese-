package it.spese.domain.health

import it.spese.core.model.Category
import it.spese.core.model.Goal
import it.spese.core.model.SpendNature
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.core.model.Valuation
import it.spese.domain.finance.AlertEngine
import it.spese.domain.finance.CategoryTree
import it.spese.domain.finance.RecurringPattern
import it.spese.domain.goals.GoalEngine
import it.spese.domain.goals.GoalStatus
import it.spese.domain.networth.NetWorthEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Parere sulla salute del bilancio, calcolato SOLO sullo storico e separando ciò che è stabile da ciò che varia:
 * entrate fisse / variabili e spese fisse / necessarie / discrezionali. Usa mediane e quartili (non medie) per non farsi spostare da un mese
 * eccezionale, ragiona «in prudenza» sulle entrate variabili (quanto hai incassato anche nei mesi magri) e indica dove agire a partire dal livello
 * che ogni categoria ha già mantenuto nei suoi mesi più sobri, non da percentuali uguali per tutte. Mai tagli sulle spese fisse.
 */
object HealthEngine {
    val TARGET_RATE = BigDecimal("0.20")
    private const val WINDOW = 12
    private val FIVE = BigDecimal(5)

    /** `forcedRef`: «oggi» di riferimento (per ricostruire il punteggio di un mese passato); altrimenti si usa AlertEngine.referenceToday. */
    fun compute(
        data: AppData, goals: List<Goal>, valuations: List<Valuation>, patterns: List<RecurringPattern>, incomeOverrides: Map<Long, IncomeNature>,
        today: LocalDate = LocalDate.now(), includeGoals: Boolean = true, forcedRef: LocalDate? = null,
    ): HealthReport? {
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        if (tx.isEmpty()) return null
        val cal = data.calendar
        val ref = forcedRef ?: AlertEngine.referenceToday(today, tx)
        val current = cal.monthOf(ref)
        val byId = data.categoriesById
        val byMonthAll = tx.groupBy { cal.monthOf(it.bookingDate) }.filterKeys { it < current }
        var months = byMonthAll.keys.sorted().takeLast(WINDOW)
        if (months.isEmpty()) return null
        // il primo mese in assoluto può essere parziale (conto aperto a metà mese): con abbastanza storia lo escludo
        if (months.size >= 4 && months.first() == byMonthAll.keys.min()) {
            val firstDay = byMonthAll.getValue(months.first()).minOf { it.bookingDate }
            if (firstDay.isAfter(cal.start(months.first()).plusDays(7))) months = months.drop(1)
        }
        val n = months.size
        val provisional = n < 3
        val byMonth = months.associateWith { byMonthAll.getValue(it) }

        // ---- entrate: fonte per fonte, fisse o variabili ----
        val incomeBy = months.associateWith { m -> byMonth.getValue(m).filter { it.kind == TxKind.ENTRATA }.groupBy { CategoryTree.rootId(it.categoryId, byId) }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a + t.amount } } }
        val incomeIds = incomeBy.values.flatMap { it.keys }.toSet()
        fun presentValues(id: Long?) = months.mapNotNull { m -> incomeBy.getValue(m)[id]?.takeIf { it.signum() > 0 } }
        fun detectIncome(id: Long?): IncomeNature {
            val vals = presentValues(id)
            val needPresent = if (n >= 3) ceil(0.7 * n).toInt() else n
            return if (vals.size >= needPresent && vals.isNotEmpty() && HealthStats.cv(vals) <= 0.15) IncomeNature.FIXED else IncomeNature.VARIABLE
        }
        val sources = incomeIds.map { id ->
            val det = detectIncome(id); val ov = id?.let { incomeOverrides[it] }
            val vals = presentValues(id)
            IncomeSource(id, id?.let { byId[it]?.name } ?: "Senza categoria", HealthStats.median(vals), vals.size, ov ?: det, det, ov != null && ov != det)
        }.sortedByDescending { it.monthlyMedian }
        val natureOf = sources.associate { it.categoryId to it.nature }
        val fixedIncomeM = months.map { m -> incomeBy.getValue(m).filterKeys { natureOf[it] == IncomeNature.FIXED }.values.fold(BigDecimal.ZERO) { a, v -> a + v } }
        val varIncomeM = months.map { m -> incomeBy.getValue(m).filterKeys { natureOf[it] != IncomeNature.FIXED }.values.fold(BigDecimal.ZERO) { a, v -> a + v } }
        val incomeM = fixedIncomeM.zip(varIncomeM) { a, b -> a + b }
        val typicalIncome = HealthStats.median(incomeM)
        if (typicalIncome.signum() <= 0) return null
        val incomeFixed = HealthStats.median(fixedIncomeM)
        val incomeVarTypical = HealthStats.median(varIncomeM)
        val incomeVarPrudent = HealthStats.percentile(varIncomeM, 0.25)
        val prudentIncome = incomeFixed + incomeVarPrudent

        // ---- spese: categoria per categoria, fisse / necessarie / discrezionali ----
        val expBy = months.associateWith { m -> byMonth.getValue(m).filter { it.kind == TxKind.SPESA }.groupBy { CategoryTree.rootId(it.categoryId, byId) }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() } } }
        val expIds = expBy.values.flatMap { it.keys }.toSet()
        fun classify(id: Long?): Pair<ExpenseClass, Boolean> {
            val cat = id?.let { byId[it] }
            return when (cat?.nature) {
                SpendNature.FIXED -> ExpenseClass.FIXED to false
                SpendNature.ESSENTIAL -> ExpenseClass.NECESSARY to false
                SpendNature.DISCRETIONARY -> ExpenseClass.DISCRETIONARY to false
                null -> {
                    val vals = months.mapNotNull { m -> expBy.getValue(m)[id]?.takeIf { it.signum() > 0 } }
                    when {
                        n >= 3 && vals.size >= ceil(0.75 * n).toInt() && HealthStats.cv(vals) <= 0.12 -> ExpenseClass.FIXED to true     // importo regolare ogni mese: probabilmente fissa
                        cat?.flexible == true -> ExpenseClass.DISCRETIONARY to true
                        else -> ExpenseClass.UNCLASSIFIED to true
                    }
                }
            }
        }
        val flows = expIds.map { id ->
            val (cls, inferred) = classify(id)
            val series = months.map { m -> expBy.getValue(m)[id] ?: BigDecimal.ZERO }
            val last = series.takeLast(minOf(3, n)); val prev = series.dropLast(last.size).takeLast(3)
            CategoryFlow(id?.let { byId[it] }, cls, inferred, HealthStats.median(series), HealthStats.percentile(series, 0.25), HealthStats.median(last), if (prev.isEmpty()) BigDecimal.ZERO else HealthStats.median(prev), series)
        }.sortedByDescending { it.median }
        fun classSeries(cls: ExpenseClass) = months.indices.map { i -> flows.filter { it.cls == cls }.fold(BigDecimal.ZERO) { a, f -> a + f.series[i] } }
        val fixedM = classSeries(ExpenseClass.FIXED); val necM = classSeries(ExpenseClass.NECESSARY); val discM = classSeries(ExpenseClass.DISCRETIONARY); val uncM = classSeries(ExpenseClass.UNCLASSIFIED)
        val expF = HealthStats.median(fixedM); val expN = HealthStats.median(necM); val expD = HealthStats.median(discM); val expU = HealthStats.median(uncM)
        val expTotalM = months.indices.map { i -> fixedM[i] + necM[i] + discM[i] + uncM[i] }
        val totalSpent = expTotalM.fold(BigDecimal.ZERO) { a, v -> a + v }
        val explicitSpent = flows.filter { !it.inferred }.fold(BigDecimal.ZERO) { a, f -> a + f.series.fold(BigDecimal.ZERO) { s, v -> s + v } }
        val classifiedPercent = if (totalSpent.signum() > 0) explicitSpent.multiply(BigDecimal(100)).divide(totalSpent, 0, RoundingMode.HALF_UP).toInt() else 100

        // ---- risparmio: tipico, prudente, obiettivo del 20% ----
        val netM = months.indices.map { i -> incomeM[i] - expTotalM[i] }
        val typicalSaving = HealthStats.median(netM)
        val rate = typicalSaving.toDouble() / typicalIncome.toDouble()
        val expectedExpenses = expF + expN + expD + expU
        val prudentSaving = prudentIncome - expectedExpenses
        val target = prudentIncome.multiply(TARGET_RATE).setScale(2, RoundingMode.HALF_UP)
        val gap = target - prudentSaving

        // ---- indicatori ----
        fun eur(v: BigDecimal) = it.spese.core.common.Money.eur0(v)
        fun pct(x: Double) = "${(x * 100).roundToInt()}%"
        val needsBase = expF + expN + expU.divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
        val ind = ArrayList<Indicator>()

        run { // risparmio
            val s = HealthStats.piecewise(rate, -0.10 to 0.0, 0.0 to 25.0, 0.20 to 100.0)
            val lvl = if (rate >= 0.20) Level.GOOD else if (rate >= 0.10) Level.WARN else Level.BAD
            ind.add(Indicator("saving", "Risparmio", pct(rate), lvl, s.roundToInt(), 25,
                "Metti da parte il ${pct(rate)} delle entrate tipiche (obiettivo 20%, cioè ${eur(target)}). In prudenza, contando della parte variabile delle entrate solo ciò che hai incassato anche nei mesi magri, il margine è ${eur(prudentSaving)} al mese."))
        }
        run { // bisogni: fisse + necessarie su entrate prudenti
            val r = needsBase.toDouble() / prudentIncome.toDouble().coerceAtLeast(1.0)
            val s = HealthStats.piecewise(r, 0.50 to 100.0, 0.65 to 55.0, 0.85 to 10.0, 1.0 to 0.0)
            val lvl = if (r <= 0.50) Level.GOOD else if (r <= 0.65) Level.WARN else Level.BAD
            ind.add(Indicator("needs", "Bisogni sulle entrate prudenti", pct(r), lvl, s.roundToInt(), 20,
                "Spese fisse e necessarie (${eur(needsBase)} al mese) assorbono il ${pct(r)} delle entrate prudenti (${eur(prudentIncome)}). Di norma si resta entro il 50%."))
        }
        run { // peso delle spese fisse sulle entrate fisse
            val base = if (incomeFixed.signum() > 0) incomeFixed else prudentIncome
            val r = expF.toDouble() / base.toDouble().coerceAtLeast(1.0)
            val s = HealthStats.piecewise(r, 0.35 to 100.0, 0.50 to 70.0, 0.70 to 25.0, 0.90 to 0.0)
            val lvl = if (r <= 0.40) Level.GOOD else if (r <= 0.60) Level.WARN else Level.BAD
            ind.add(Indicator("fixed", "Spese fisse sulle entrate fisse", pct(r), lvl, s.roundToInt(), 10,
                if (incomeFixed.signum() > 0) "Le spese fisse (${eur(expF)}) assorbono il ${pct(r)} delle entrate fisse (${eur(incomeFixed)}): più è alto, meno margine hai se qualcosa cambia."
                else "Non riconosco entrate fisse: confronto le spese fisse (${eur(expF)}) con le entrate prudenti (${eur(prudentIncome)})."))
        }
        run { // dipendenza dalle entrate variabili
            val stress = months.indices.count { incomeM[it] < expF + expN }
            val share = stress.toDouble() / n
            val varShare = if (typicalIncome.signum() > 0) incomeVarTypical.toDouble() / typicalIncome.toDouble() else 0.0
            val s = HealthStats.piecewise(share, 0.0 to 100.0, 0.17 to 75.0, 0.5 to 25.0, 0.7 to 0.0)
            val lvl = if (stress == 0) Level.GOOD else if (share <= 0.25) Level.WARN else Level.BAD
            ind.add(Indicator("variable", "Dipendenza dalle entrate variabili", "$stress/$n mesi", lvl, s.roundToInt(), 15,
                "Le entrate variabili sono circa il ${pct(varShare)} del totale. In $stress ${if (n == 1) "mese" else "mesi"} su $n le entrate non hanno coperto nemmeno le spese fisse e necessarie (${eur(expF + expN)})."))
        }
        val liquidity = runCatching { NetWorthEngine.compute(data, goals, valuations, ref)?.liquidity }.getOrNull()
        run { // fondo di emergenza, tarato sulla stabilità delle entrate
            val varShare = incomeVarTypical.toDouble() / typicalIncome.toDouble()
            val (minM, recM) = if (varShare < 0.20) 3 to 6 else if (varShare < 0.50) 4 to 8 else 6 to 9
            val monthlyNeed = needsBase
            if (liquidity == null || monthlyNeed.signum() <= 0) ind.add(Indicator("fund", "Fondo di emergenza", "—", Level.NA, null, 15, "Servono un conto e le spese essenziali per calcolarlo."))
            else {
                val months1 = liquidity.max(BigDecimal.ZERO).divide(monthlyNeed, 1, RoundingMode.DOWN)
                val m = months1.toDouble()
                val s = (100.0 * m / recM).coerceIn(0.0, 100.0)
                val lvl = if (m >= recM) Level.GOOD else if (m >= minM) Level.WARN else Level.BAD
                ind.add(Indicator("fund", "Fondo di emergenza", "${months1.toPlainString().replace('.', ',')} mesi", lvl, s.roundToInt(), 15,
                    "La liquidità (${eur(liquidity)}) copre ${months1.toPlainString().replace('.', ',')} mesi di spese essenziali. Con le tue entrate ${if (varShare < 0.20) "stabili" else "in parte variabili"} servono almeno $minM mesi, meglio $recM."))
            }
        }
        run { // tendenza delle spese variabili
            if (n < 4) ind.add(Indicator("trend", "Tendenza delle spese variabili", "—", Level.NA, null, 5, "Servono almeno 4 mesi per vedere una tendenza."))
            else {
                val varM = months.indices.map { necM[it] + discM[it] + uncM[it] }
                val last = varM.takeLast(3); val prev = varM.dropLast(3).takeLast(3)
                val p = HealthStats.median(prev); val r = HealthStats.median(last)
                if (p.signum() <= 0) ind.add(Indicator("trend", "Tendenza delle spese variabili", "—", Level.NA, null, 5, "Non ci sono abbastanza mesi precedenti per confrontare."))
                else {
                    val ch = (r - p).toDouble() / p.toDouble()
                    val s = HealthStats.piecewise(ch, 0.0 to 100.0, 0.25 to 0.0)
                    val lvl = if (ch <= 0.05) Level.GOOD else if (ch <= 0.15) Level.WARN else Level.BAD
                    ind.add(Indicator("trend", "Tendenza delle spese variabili", (if (ch >= 0) "+" else "−") + pct(kotlin.math.abs(ch)), lvl, s.roundToInt(), 5,
                        "Le spese variabili degli ultimi 3 mesi (${eur(r)} al mese) sono ${if (ch >= 0) "sopra" else "sotto"} quelle dei 3 mesi prima (${eur(p)}) del ${pct(kotlin.math.abs(ch))}."))
                }
            }
        }
        run { // regolarità dei mesi in positivo
            val pos = netM.count { it.signum() > 0 }
            val share = pos.toDouble() / n
            val lvl = if (share >= 0.80) Level.GOOD else if (share >= 0.55) Level.WARN else Level.BAD
            ind.add(Indicator("regular", "Mesi chiusi in positivo", "$pos/$n", lvl, (share * 100).roundToInt(), 5, "Hai chiuso in positivo $pos mesi su $n."))
        }
        if (includeGoals && goals.isNotEmpty()) {
            val st = GoalEngine.all(goals, ref).filter { it.status == GoalStatus.ON_TRACK || it.status == GoalStatus.LATE || it.status == GoalStatus.COMPLETED }
            if (st.isNotEmpty()) {
                val ok = st.count { it.status != GoalStatus.LATE }
                ind.add(Indicator("goals", "Obiettivi", "$ok/${st.size} in linea", if (ok == st.size) Level.GOOD else if (ok > 0) Level.WARN else Level.BAD, (100.0 * ok / st.size).roundToInt(), 5,
                    "$ok obiettivi su ${st.size} sono in linea con la scadenza o già raggiunti."))
            }
        }
        val scored = ind.filter { it.score != null }
        val score = if (scored.isEmpty()) 0 else (scored.sumOf { it.score!! * it.weight }.toDouble() / scored.sumOf { it.weight }).roundToInt().coerceIn(0, 100)
        var verdict = if (score >= 75) Verdict.HEALTHY else if (score >= 55) Verdict.ATTENTION else if (score >= 35) Verdict.FRAGILE else Verdict.DIFFICULTY
        val notes = ArrayList<String>()
        // tetti: un punteggio medio non può nascondere un bilancio che non regge
        if (typicalSaving.signum() < 0 && verdict < Verdict.FRAGILE) { verdict = Verdict.FRAGILE; notes.add("Nei mesi tipici spendi più di quanto incassi: il giudizio non può essere migliore di «Fragile».") }
        val stressShare = months.indices.count { incomeM[it] < expF + expN }.toDouble() / n
        if (stressShare >= 0.5 && verdict < Verdict.FRAGILE) { verdict = Verdict.FRAGILE; notes.add("In metà dei mesi le entrate non coprono le spese fisse e necessarie: il giudizio non può essere migliore di «Fragile».") }

        // ---- dove agire: livelli già mantenuti dall'utente, non percentuali uguali per tutte ----
        fun growth(f: CategoryFlow) = if (f.previous.signum() > 0) (f.recent - f.previous).toDouble() / f.previous.toDouble() else 0.0
        data class Cand(val f: CategoryFlow, val floor: BigDecimal, val reducible: BigDecimal, val rank: Double)
        fun candidates(cls: ExpenseClass, keep: Double): List<Cand> = flows.filter { it.cls == cls && it.category != null && it.median.signum() > 0 }.mapNotNull { f ->
            val floor = f.p25.max(f.median.multiply(BigDecimal(keep))).setScale(2, RoundingMode.HALF_UP)
            val red = f.median - floor
            if (red < FIVE) null else Cand(f, floor, red, red.toDouble() * (1 + growth(f).coerceIn(0.0, 0.5)))
        }.sortedByDescending { it.rank }
        val levers = ArrayList<Lever>()
        var remaining = gap
        if (gap.signum() > 0) {
            for (c in candidates(ExpenseClass.DISCRETIONARY, 0.65) + candidates(ExpenseClass.NECESSARY, 0.85)) {
                if (remaining.signum() <= 0) break
                val saving = c.reducible.min(remaining)
                val tgt = c.f.median - saving
                val g = growth(c.f)
                levers.add(Lever(c.f.category!!, c.f.cls, c.f.median, tgt, saving, g >= 0.10,
                    "Nei tuoi mesi più sobri hai speso ${eur(c.floor)}: il livello tipico è ${eur(c.f.median)}." + if (g >= 0.10) " Negli ultimi 3 mesi è salita del ${pct(g)}." else "",
                    HealthStats.ceil5(tgt)))
                remaining -= saving
            }
        }
        val leversSaving = levers.fold(BigDecimal.ZERO) { a, l -> a + l.saving }

        // ---- spese fisse da rivedere (mai tagliate a forza) ----
        val watch = ArrayList<WatchItem>()
        patterns.filter { it.priceChanged && (it.priceDelta ?: BigDecimal.ZERO).signum() > 0 && !it.stale }.forEach { p ->
            watch.add(WatchItem("${p.merchant} è aumentato", "Da ${it.spese.core.common.Money.eur(p.previousAmount!!)} a ${it.spese.core.common.Money.eur(p.lastAmount)}: chiedi se il servizio vale ancora il prezzo.", p.perMonth - p.previousAmount!!.divide(BigDecimal(p.cadence.months), 2, RoundingMode.HALF_UP)))
        }
        patterns.filter { it.stale }.forEach { p -> watch.add(WatchItem("${p.merchant}: addebito non più arrivato", "Probabilmente disdetto: se è così, non è più una spesa.", null)) }
        patterns.filter { (it.remainingInstallments ?: 99) in 1..3 && !it.finished }.forEach { p ->
            watch.add(WatchItem("La rata «${p.merchant}» sta per finire", "Restano ${p.remainingInstallments} rate: dopo, ${it.spese.core.common.Money.eur0(p.perMonth)} al mese si liberano (da destinare a risparmio, non a nuove spese).", p.perMonth))
        }
        flows.filter { it.cls == ExpenseClass.FIXED && it.previous.signum() > 0 && it.recent > it.previous.multiply(BigDecimal("1.10")) && it.recent - it.previous >= BigDecimal(10) }.forEach { f ->
            watch.add(WatchItem("«${f.name}» è salita", "Dalla media di ${eur(f.previous)} a ${eur(f.recent)} al mese negli ultimi 3 mesi: una spesa fissa che cresce pesa ogni mese.", f.recent - f.previous))
        }

        // ---- affidabilità del parere ----
        if (provisional) notes.add("Parere provvisorio: ho solo $n ${if (n == 1) "mese completo" else "mesi completi"} (ne servono almeno 3 per distinguere fisso e variabile).")
        if (classifiedPercent < 70) {
            val top = flows.filter { it.inferred }.take(3).joinToString(", ") { it.name }
            notes.add("Solo il $classifiedPercent% delle spese ha una natura scelta da te (fissa, necessaria, discrezionale): per le altre l'ho dedotta dalla regolarità degli importi. Assegnala in Altro › Categorie per un parere più preciso" + if (top.isNotEmpty()) " (le più pesanti: $top)." else ".")
        }
        if (data.unassigned.isNotEmpty()) notes.add("${data.unassigned.size} movimenti senza conto non sono contati: assegnali da Movimenti › Verifica.")
        if (sources.any { it.nature == IncomeNature.VARIABLE }) notes.add("Ho riconosciuto come variabili le entrate da: " + sources.filter { it.nature == IncomeNature.VARIABLE }.take(3).joinToString(", ") { it.name } + ". Se una è in realtà fissa, correggila qui sotto.")

        val report = HealthReport(
            ref, months, provisional, score, verdict, incomeFixed, incomeVarTypical, incomeVarPrudent, typicalIncome, prudentIncome, sources,
            expF, expN, expD, expU, flows, classifiedPercent, typicalSaving, (rate * 100).roundToInt(), prudentSaving, target, gap, ind, levers, leversSaving, remaining.max(BigDecimal.ZERO), watch, notes, "",
        )
        return report.copy(headline = HealthText.headline(report))
    }

    /** Unico ingresso per Home e schermata di dettaglio: rileva da sé abbonamenti e rate (senza quelli ignorati o già coperti da una ricorrenza). */
    fun forApp(data: AppData, goals: List<Goal>, valuations: List<Valuation>, rules: List<it.spese.core.model.RecurringRule>, ignoredPatterns: Set<String>, overrides: Map<Long, IncomeNature>, today: LocalDate = LocalDate.now()): HealthReport? {
        if (!data.loaded || data.transactions.isEmpty()) return null
        val patterns = it.spese.domain.finance.SubscriptionDetector.detect(data.transactions, it.spese.domain.finance.SubscriptionDetector.excludedCategories(data.categories), today, rules).filter { p -> p.key !in ignoredPatterns }
        return compute(data, goals, valuations, patterns, overrides, today)
    }

    /** Punteggio di ogni mese concluso (ultimi `count`), ricostruito con i soli dati disponibili allora: per vedere se si migliora. Senza obiettivi (non si può sapere com'erano). */
    fun history(data: AppData, valuations: List<Valuation>, incomeOverrides: Map<Long, IncomeNature>, today: LocalDate = LocalDate.now(), count: Int = 6): List<Pair<YearMonth, Int>> {
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        if (tx.isEmpty()) return emptyList()
        val cal = data.calendar
        val current = cal.monthOf(AlertEngine.referenceToday(today, tx))
        return tx.map { cal.monthOf(it.bookingDate) }.distinct().filter { it < current }.sorted().takeLast(count).mapNotNull { m ->
            val cutoff = cal.start(m.plusMonths(1))
            val d = data.copy(transactions = data.transactions.filter { it.bookingDate < cutoff }, ledger = data.ledger.filter { it.bookingDate < cutoff })
            compute(d, emptyList(), valuations, emptyList(), incomeOverrides, cutoff, includeGoals = false, forcedRef = cutoff)?.let { m to it.score }
        }
    }
}
