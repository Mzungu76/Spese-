package it.spese.domain.report

import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TransactionSplit
import it.spese.core.model.TxKind
import it.spese.core.model.TxOrigin
import it.spese.domain.classification.merchant
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import it.spese.domain.simulation.Trends
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Dai dati e dalle scelte dell'utente al contenuto del report. Riusa gli stessi motori della Panoramica, delle Tendenze e della Verifica,
 * così i numeri stampati sono quelli che l'app mostra. Nessuna dipendenza da Android: si prova con JUnit puro.
 */
object ReportBuilder {
    private val PALETTE = intArrayOf(0xFF2358D8.toInt(), 0xFFE0742C.toInt(), 0xFF16A37F.toInt(), 0xFFC23B8F.toInt(), 0xFF8A5CF6.toInt(), 0xFFD4A017.toInt(), 0xFF0EA5C6.toInt(), 0xFFD64545.toInt(), 0xFF5B7C2F.toInt(), 0xFF7A6A5A.toInt())
    private val GREEN = 0xFF16A37F.toInt(); private val RED = 0xFFD64545.toInt()

    fun build(data: AppData, spec: ReportSpec, today: LocalDate = LocalDate.now(), presetTitle: String? = null): ReportDocument {
        val dataMax = data.ledger.maxOfOrNull { it.bookingDate }
        val period = spec.period.resolve(dataMax)
        val filter = TxFilter(period = period, accountId = spec.accountId)
        val model = DashboardCalculator.compute(data, filter, today)
        val range = if (data.ledger.isEmpty()) DateRange(null, null) else PeriodResolver.resolve(period, data.ledger.minOf { it.bookingDate }, dataMax!!)
        val byId = data.categoriesById
        val selected: Set<Long>? = spec.categoryIds.takeIf { it.isNotEmpty() }?.flatMap { CategoryTree.withChildren(it, data.categories) }?.toSet()

        val title = spec.title.ifBlank { presetTitle ?: "Report" }
        val periodText = when {
            range.from != null && range.to != null -> "${spec.period.label().takeIf { spec.period.kind == ReportPeriodKind.MONTH || spec.period.kind == ReportPeriodKind.CUSTOM }?.plus(" · ") ?: ""}${Dates.fmt(range.from)} – ${Dates.fmt(range.to)}"
            else -> spec.period.label()
        }
        val subtitle = buildList {
            add(periodText)
            add(spec.accountId?.let { data.accountName(it) } ?: "tutti i conti")
            selected?.let { add("categorie: " + spec.categoryIds.mapNotNull { byId[it]?.name }.joinToString(", ")) }
        }.joinToString(" · ")

        val blocks = ArrayList<Block>()
        if (data.ledger.isEmpty() || model.filtered.isEmpty() && spec.sections.none { it == ReportSection.TRANSACTIONS || it == ReportSection.CASH || it == ReportSection.BUDGETS }) {
            return ReportDocument(title, subtitle, "Creato il " + Dates.fmt(today), spec.note, listOf(Block.Paragraph("Nessun movimento nel periodo scelto.", Tone.MUTED)), spec.landscape, empty = true)
        }
        val ctx = Ctx(data, spec, model, range, selected, today)
        spec.sections.distinct().forEach { sec ->
            when (sec) {
                ReportSection.SUMMARY -> summary(ctx, blocks)
                ReportSection.MONTHLY -> monthly(ctx, blocks)
                ReportSection.CATEGORIES -> categories(ctx, blocks)
                ReportSection.CHANGES -> changes(ctx, blocks)
                ReportSection.MERCHANTS -> merchants(ctx, blocks)
                ReportSection.TOP_EXPENSES -> topExpenses(ctx, blocks)
                ReportSection.BUDGETS -> budgets(ctx, blocks)
                ReportSection.TRENDS -> trends(ctx, blocks, filter)
                ReportSection.CASH -> cash(ctx, blocks)
                ReportSection.TRANSACTIONS -> transactions(ctx, blocks)
            }
        }
        return ReportDocument(title, subtitle, "Creato il " + Dates.fmt(today), spec.note, blocks, spec.landscape)
    }

    private class Ctx(val data: AppData, val spec: ReportSpec, val m: DashboardModel, val range: DateRange, val selected: Set<Long>?, val today: LocalDate) {
        val s get() = m.stats
        val byId get() = data.categoriesById
        /** Movimenti del periodo (con le parti dei movimenti divisi), ristretti alle categorie scelte. */
        val catTx: List<Transaction> get() = if (selected == null) m.filtered else m.filtered.filter { it.categoryId in selected }
    }

    private fun eur(v: BigDecimal) = Money.eur(v)
    private fun eur0(v: BigDecimal) = Money.eur0(v)
    private fun pct(v: BigDecimal?) = v?.let { it.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "%" } ?: "–"
    private fun signedPct(v: BigDecimal?) = v?.let { (if (it.signum() > 0) "+" else "") + it.setScale(0, RoundingMode.HALF_UP).toPlainString() + "%" } ?: "–"
    private fun signed0(v: BigDecimal) = (if (v.signum() > 0) "+" else "") + eur0(v)
    private fun colorOf(c: Category?, i: Int) = c?.color?.toInt()?.let { it or 0xFF000000.toInt() } ?: PALETTE[i % PALETTE.size]
    private fun cut(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1) + "…"

    private fun summary(c: Ctx, out: MutableList<Block>) {
        val s = c.s; val m = c.m
        out += Block.Heading("Riepilogo")
        out += Block.Kpis(listOf(
            Kpi("Entrate", eur0(s.income), m.incomeChange?.let { signedPct(it) + " vs prec." }, Tone.POSITIVE),
            Kpi("Spese", eur0(s.expense), m.expenseChange?.let { signedPct(it) + " vs prec." }, Tone.NEGATIVE),
            Kpi("Risparmio", eur0(s.net), s.savingsRatePercent?.let { "$it% delle entrate" }, if (s.net.signum() >= 0) Tone.POSITIVE else Tone.NEGATIVE),
            Kpi("Spesa media / mese", eur0(m.monthlyAvgExpense), null),
        ))
        val prev = m.previous
        if (prev != null && m.previousLabel != null && (prev.income.signum() != 0 || prev.expense.signum() != 0)) {
            fun row(label: String, now: BigDecimal, before: BigDecimal, goodUp: Boolean): TRow {
                val d = now - before; val p = Trends.changePercent(before, now)
                val tone = if (d.signum() == 0) Tone.MUTED else if ((d.signum() > 0) == goodUp) Tone.POSITIVE else Tone.NEGATIVE
                return TRow(listOf(label, eur0(now), eur0(before), signed0(d), signedPct(p)), tones = mapOf(3 to tone, 4 to tone))
            }
            out += Block.Table("Confronto " + m.previousLabel, listOf(Col("", weight = 1.6f), Col("Periodo", Align.RIGHT), Col("Precedente", Align.RIGHT), Col("Differenza", Align.RIGHT), Col("%", Align.RIGHT, 0.7f)),
                listOf(row("Entrate", s.income, prev.income, true), row("Spese", s.expense, prev.expense, false), row("Risparmio", s.net, prev.net, true)))
        }
        if (s.transfers.signum() != 0) out += Block.Paragraph("Giroconti tra i tuoi conti, esclusi dai totali: ${eur0(s.transfers.abs())}.", Tone.MUTED)
    }

    private fun monthly(c: Ctx, out: MutableList<Block>) {
        val buckets = TrendBuckets.compute(c.m.filtered)
        if (buckets.isEmpty()) return
        out += Block.Heading("Andamento nel tempo")
        if (c.spec.charts && buckets.size >= 2) out += Block.Columns(null, buckets.map { it.label }, listOf(
            Series("Entrate", GREEN, buckets.map { it.income.toDouble() }), Series("Spese", RED, buckets.map { it.expense.toDouble() })))
        if (buckets.size <= 40) out += Block.Table(null, listOf(Col(if (buckets.first().weekly) "Settimana" else "Mese", weight = 1.4f), Col("Entrate", Align.RIGHT), Col("Spese", Align.RIGHT), Col("Risparmio", Align.RIGHT)),
            buckets.map { b -> TRow(listOf(b.title.replaceFirstChar { it.uppercase() }, eur0(b.income), eur0(b.expense), eur0(b.net)), tones = mapOf(3 to if (b.net.signum() >= 0) Tone.POSITIVE else Tone.NEGATIVE)) },
            TRow(listOf("Totale", eur0(c.s.income), eur0(c.s.expense), eur0(c.s.net)), bold = true))
    }

    private fun categories(c: Ctx, out: MutableList<Block>) {
        val s = c.s; val m = c.m; val byId = c.byId
        val sel = c.selected
        val rows = m.categories.filter { sel == null || it.category?.id in sel }
        out += Block.Heading("Spese per categoria")
        if (rows.isEmpty()) { out += Block.Paragraph("Nessuna spesa nelle categorie scelte.", Tone.MUTED) } else {
            if (c.spec.charts) out += Block.Bars(null, rows.take(10).mapIndexed { i, r -> BarItem(r.category?.name ?: "Senza categoria", r.total.toDouble(), eur0(r.total), colorOf(r.category, i)) })
            val trows = ArrayList<TRow>()
            rows.forEach { r ->
                val id = r.category?.id
                val vsIncome = if (s.income.signum() > 0) Money.percent(r.total, s.income) else null
                trows += TRow(listOf(r.category?.name ?: "Senza categoria", eur0(r.total), pct(r.percentOfExpense), pct(vsIncome), eur0(r.monthlyAvg), signedPct(r.changePercent)),
                    bold = true, tones = mapOf(5 to if (r.surging) Tone.NEGATIVE else Tone.MUTED))
                if (c.spec.subcategories && id != null) {
                    CategoryTree.children(id, c.data.categories).mapNotNull { k -> s.byCategory[k.id]?.takeIf { it.signum() > 0 }?.let { k to it } }.sortedByDescending { it.second }.forEach { (k, v) ->
                        trows += TRow(listOf(k.name, eur0(v), pct(Money.percent(v, s.expense)), pct(if (s.income.signum() > 0) Money.percent(v, s.income) else null), eur0(v.divide(m.months, 2, RoundingMode.HALF_UP)), ""), indent = true, tones = mapOf(0 to Tone.MUTED))
                    }
                }
            }
            val total = rows.fold(BigDecimal.ZERO) { a, r -> a + r.total }
            out += Block.Table(null, listOf(Col("Categoria", weight = 2.2f), Col("Importo", Align.RIGHT), Col("% spese", Align.RIGHT, 0.8f), Col("% entrate", Align.RIGHT, 0.9f), Col("Media/mese", Align.RIGHT), Col("Var.", Align.RIGHT, 0.7f)), trows,
                TRow(listOf(if (sel == null) "Totale spese" else "Totale scelte", eur0(total), pct(Money.percent(total, s.expense)), pct(if (s.income.signum() > 0) Money.percent(total, s.income) else null), eur0(total.divide(m.months, 2, RoundingMode.HALF_UP)), ""), bold = true))
        }
        val inc = m.incomeCategories.filter { sel == null || it.category?.id in sel }
        if (inc.isNotEmpty()) {
            out += Block.Table("Entrate per categoria", listOf(Col("Categoria", weight = 2.2f), Col("Importo", Align.RIGHT), Col("% entrate", Align.RIGHT, 0.9f), Col("Media/mese", Align.RIGHT), Col("Var.", Align.RIGHT, 0.7f)),
                inc.map { r -> TRow(listOf(r.category?.name ?: "Senza categoria", eur0(r.total), pct(r.percentOfExpense), eur0(r.monthlyAvg), signedPct(r.changePercent)), tones = mapOf(4 to Tone.MUTED)) },
                TRow(listOf("Totale entrate", eur0(inc.fold(BigDecimal.ZERO) { a, r -> a + r.total }), "", "", ""), bold = true))
        }
    }

    private fun changes(c: Ctx, out: MutableList<Block>) {
        out += Block.Heading("Cosa è cambiato")
        val m = c.m
        if (m.previous == null || m.previousLabel == null) { out += Block.Paragraph("Con questo periodo non c'è un periodo precedente confrontabile.", Tone.MUTED); return }
        val rows = m.categories.filter { c.selected == null || it.category?.id in c.selected }.filter { (it.delta ?: BigDecimal.ZERO).signum() != 0 }
            .sortedByDescending { it.delta!!.abs() }.take(c.spec.topN)
        if (rows.isEmpty()) { out += Block.Paragraph("Nessuna variazione di rilievo " + m.previousLabel + ".", Tone.MUTED); return }
        out += Block.Table("Le categorie che cambiano di più " + m.previousLabel, listOf(Col("Categoria", weight = 2f), Col("Prima", Align.RIGHT), Col("Ora", Align.RIGHT), Col("Differenza", Align.RIGHT), Col("%", Align.RIGHT, 0.7f)),
            rows.map { r -> val d = r.delta!!; val tone = if (d.signum() > 0) Tone.NEGATIVE else Tone.POSITIVE
                TRow(listOf(r.category?.name ?: "Senza categoria", eur0(r.previousTotal ?: BigDecimal.ZERO), eur0(r.total), signed0(d), signedPct(r.changePercent)), tones = mapOf(3 to tone, 4 to tone)) })
    }

    private fun merchants(c: Ctx, out: MutableList<Block>) {
        val list = StatsEngine.topMerchants(StatsEngine.compute(c.catTx), c.spec.topN)
        if (list.isEmpty()) return
        out += Block.Heading("Principali esercenti")
        val exp = StatsEngine.compute(c.catTx).expense
        if (c.spec.charts) out += Block.Bars(null, list.take(8).mapIndexed { i, (n, st) -> BarItem(cut(n, 30), st.total.toDouble(), eur0(st.total), PALETTE[i % PALETTE.size]) })
        out += Block.Table(null, listOf(Col("Esercente", weight = 2.5f), Col("Movimenti", Align.RIGHT, 0.9f), Col("Totale", Align.RIGHT), Col("% spese", Align.RIGHT, 0.8f)),
            list.map { (n, st) -> TRow(listOf(n, st.count.toString(), eur0(st.total), pct(Money.percent(st.total, exp)))) })
    }

    private fun topExpenses(c: Ctx, out: MutableList<Block>) {
        val list = c.catTx.filter { it.kind == TxKind.SPESA }.sortedBy { it.amount }.take(c.spec.topN)
        if (list.isEmpty()) return
        out += Block.Heading("Spese singole più alte")
        out += Block.Table(null, listOf(Col("Data", weight = 0.9f), Col("Descrizione", weight = 2.4f), Col("Categoria", weight = 1.5f), Col("Importo", Align.RIGHT)),
            list.map { t -> TRow(listOf(Dates.fmt(t.bookingDate), cut(t.merchant, 40), c.data.categoryName(t.categoryId), eur(t.amount.negate()))) })
    }

    private fun budgets(c: Ctx, out: MutableList<Block>) {
        val m = c.m
        out += Block.Heading("Budget e obiettivo")
        val goal = m.goalMonthly
        if (goal.signum() > 0) out += Block.Kpis(listOf(
            Kpi("Obiettivo di risparmio", eur0(goal) + "/mese", null),
            Kpi("Risparmio nel periodo", eur0(m.savingsPerMonth) + "/mese", if (m.savingsPerMonth >= goal) "obiettivo raggiunto" else "mancano ${eur0(goal - m.savingsPerMonth)}/mese", if (m.savingsPerMonth >= goal) Tone.POSITIVE else Tone.WARNING),
        ))
        val b = m.budgets.filter { c.selected == null || it.category.id in c.selected }
        if (b.isEmpty()) out += Block.Paragraph("Nessun budget impostato" + (if (c.selected != null) " per le categorie scelte" else "") + ".", Tone.MUTED)
        else out += Block.Table("Budget di " + Dates.monthLabelLong(YearMonth.from(m.today)), listOf(Col("Categoria", weight = 2f), Col("Speso", Align.RIGHT), Col("Budget", Align.RIGHT), Col("Usato", Align.RIGHT, 0.8f), Col("Stato", Align.CENTER, 1.1f)),
            b.map { u -> val p = u.percent.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)
                val (txt, tone) = when { u.exceeded -> "Superato" to Tone.NEGATIVE; p >= BigDecimal(80) -> "Vicino" to Tone.WARNING; else -> "Nei limiti" to Tone.POSITIVE }
                TRow(listOf(u.category.name, eur0(u.spent), eur0(u.budget), p.toPlainString() + "%", txt), tones = mapOf(3 to tone, 4 to tone)) })
        if (m.upcoming.isNotEmpty()) out += Block.Table("Scadenze e addebiti in arrivo", listOf(Col("Voce", weight = 2.2f), Col("Data", weight = 1f), Col("Importo", Align.RIGHT), Col("Origine", weight = 1.2f)),
            m.upcoming.map { u -> TRow(listOf(u.name, Dates.fmt(u.date), eur0(u.amount), if (u.manual) "scadenza" else "ricorrente"), tones = mapOf(3 to Tone.MUTED)) })
    }

    private fun trends(c: Ctx, out: MutableList<Block>, filter: TxFilter) {
        out += Block.Heading("Tendenze e proiezioni")
        val r = CategoryTrendEngine.compute(c.data, filter, c.today)
        if (r == null) { out += Block.Paragraph("Servono dei movimenti su mesi completi per calcolare le tendenze.", Tone.MUTED); return }
        out += Block.Paragraph("Il periodo (${r.periodLabel}) è confrontato con la media storica precedente. Le proiezioni prolungano la tendenza degli ultimi mesi completi: sono stime, non previsioni.", Tone.MUTED)
        fun line(label: String, now: BigDecimal, bold: Boolean = false, f: (BalanceProjection) -> BigDecimal): TRow {
            val cells = listOf(label, eur0(now)) + r.balance.map { eur0(f(it)) }
            return TRow(cells, bold = bold, tones = if (bold) r.balance.withIndex().associate { (i, b) -> i + 2 to if (f(b) >= now) Tone.POSITIVE else Tone.NEGATIVE } else emptyMap())
        }
        out += Block.Table("Bilancio mensile atteso", listOf(Col("", weight = 1.4f), Col("Oggi", Align.RIGHT), Col("1 mese", Align.RIGHT), Col("3 mesi", Align.RIGHT), Col("6 mesi", Align.RIGHT)),
            listOf(line("Entrate", r.currentIncome) { it.income }, line("Spese", r.currentExpense) { it.expense }, line("Risparmio", r.currentNet, true) { it.net }),
            TRow(listOf("Effetto cumulato", "–") + r.balance.map { signed0(it.cumulativeImpact) }, bold = true, tones = r.balance.withIndex().associate { (i, b) -> i + 2 to if (b.cumulativeImpact.signum() >= 0) Tone.POSITIVE else Tone.NEGATIVE }))
        val cats = r.categories.filter { c.selected == null || it.category?.id in c.selected }
        if (cats.isEmpty()) return
        fun dir(t: CategoryTrend) = when (t.direction) { TrendDirection.UP -> "in aumento"; TrendDirection.DOWN -> "in calo"; TrendDirection.STABLE -> "stabile"; TrendDirection.UNKNOWN -> "pochi dati" }
        out += Block.Table("Categorie: periodo, storico e proiezioni (importo mensile atteso)",
            listOf(Col("Categoria", weight = 2f), Col("Periodo", Align.RIGHT), Col("Storico", Align.RIGHT), Col("Var.", Align.RIGHT, 0.7f), Col("Tendenza", Align.CENTER, 1.1f), Col("1 mese", Align.RIGHT), Col("3 mesi", Align.RIGHT), Col("6 mesi", Align.RIGHT), Col("Impatto 6m", Align.RIGHT, 1.1f)),
            cats.take(40).map { t ->
                val imp = t.projection(6)?.balanceImpact ?: BigDecimal.ZERO
                TRow(listOf(t.category?.name ?: "", eur0(t.periodAvg), t.historyAvg?.let { eur0(it) } ?: "–", signedPct(t.changeVsHistory), dir(t), eur0(t.projection(1)?.monthly ?: BigDecimal.ZERO), eur0(t.projection(3)?.monthly ?: BigDecimal.ZERO), eur0(t.projection(6)?.monthly ?: BigDecimal.ZERO), signed0(imp)),
                    tones = mapOf(8 to if (imp.signum() >= 0) Tone.POSITIVE else Tone.NEGATIVE, 4 to if (t.confidence == Confidence.LOW) Tone.WARNING else Tone.NORMAL))
            })
        if (cats.any { it.confidence == Confidence.LOW }) out += Block.Paragraph("Le tendenze in arancione sono stime poco affidabili: pochi mesi di dati o importi molto variabili.", Tone.MUTED)
    }

    private fun cash(c: Ctx, out: MutableList<Block>) {
        out += Block.Heading("Prelievi e movimenti divisi")
        val data = c.data
        val inPeriod = data.ledger.filter { (c.spec.accountId == null || it.accountId == c.spec.accountId) && (c.range.from == null || it.bookingDate in c.range) }
        val w = inPeriod.filter { CashQuotaEngine.isWithdrawal(it, data.categoriesById) || data.splits[it.id].orEmpty().isNotEmpty() }.sortedBy { it.bookingDate }
        if (w.isEmpty()) { out += Block.Paragraph("Nessun prelievo né movimento diviso nel periodo.", Tone.MUTED); return }
        fun parts(t: Transaction): String {
            val p = data.splits[t.id].orEmpty().sortedBy { it.position }
            return if (p.isEmpty()) data.categoryName(t.categoryId) else p.joinToString(" + ") { data.categoryName(it.categoryId) + " " + eur0(it.amount.abs()) }
        }
        out += Block.Table(null, listOf(Col("Data", weight = 0.9f), Col("Descrizione", weight = 1.8f), Col("Importo", Align.RIGHT), Col("Ripartizione", weight = 2.6f), Col("Verificato", Align.CENTER, 0.9f)),
            w.map { t -> TRow(listOf(Dates.fmt(t.bookingDate), cut(t.merchant, 28), eur(t.amount.negate()), parts(t), if (t.reviewed) "sì" else "da verificare"), tones = mapOf(4 to if (t.reviewed) Tone.MUTED else Tone.WARNING)) },
            TRow(listOf("Totale", "", eur(w.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() }), "", ""), bold = true))
        val byCat = HashMap<Long?, BigDecimal>()
        w.forEach { t -> val p = data.splits[t.id].orEmpty(); if (p.isEmpty()) byCat.merge(t.categoryId, t.amount.negate()) { a, b -> a + b } else p.forEach { byCat.merge(it.categoryId, it.amount.negate()) { a, b -> a + b } } }
        out += Block.Table("Dove sono andati i prelievi", listOf(Col("Categoria", weight = 2f), Col("Importo", Align.RIGHT)),
            byCat.entries.sortedByDescending { it.value }.map { (id, v) -> TRow(listOf(data.categoryName(id), eur(v))) })
        if (data.quotas.isNotEmpty()) out += Block.Table("Quote fisse in contanti", listOf(Col("Quota", weight = 1.6f), Col("Importo", Align.RIGHT), Col("Categoria", weight = 1.6f), Col("Quando", weight = 2f)),
            data.quotas.map { q -> TRow(listOf(q.name + if (q.enabled) "" else " (spenta)", eur0(q.amount), data.categoryName(q.categoryId), q.mode.label.lowercase() + if (q.everyMonths > 1) ", ogni ${q.everyMonths} mesi" else ""), tones = mapOf(3 to Tone.MUTED)) })
    }

    private fun transactions(c: Ctx, out: MutableList<Block>) {
        val data = c.data
        val f = TxFilter(period = c.spec.period.resolve(data.ledger.maxOfOrNull { it.bookingDate }), accountId = c.spec.accountId, categoryId = null)
        val parts = FilterEngine.apply(data.transactions, f, data.categoriesById).filter { c.selected == null || it.categoryId in c.selected }
        val ids = parts.mapTo(HashSet()) { it.id }
        val list = data.ledger.filter { it.id in ids && (c.spec.includeTransfers || it.kind != TxKind.TRASFERIMENTO) }.sortedWith(compareBy({ it.bookingDate }, { it.id }))
        out += Block.Heading("Elenco movimenti")
        if (list.isEmpty()) { out += Block.Paragraph("Nessun movimento.", Tone.MUTED); return }
        val multi = data.accounts.size > 1 && c.spec.accountId == null
        val cols = buildList {
            add(Col("Data", weight = 0.9f)); add(Col("Descrizione", weight = 2.6f)); add(Col("Categoria", weight = 1.6f)); if (multi) add(Col("Conto", weight = 1f)); add(Col("Importo", Align.RIGHT, 1.1f))
        }
        fun cat(t: Transaction): String { val p = data.splits[t.id].orEmpty(); return if (p.isNotEmpty()) "Diviso (${p.size})" else data.categoryName(t.categoryId) }
        fun row(t: Transaction) = TRow(buildList {
            add(Dates.fmt(t.bookingDate)); add(t.merchant + if (t.origin == TxOrigin.NOTIFICATION) " *" else ""); add(cat(t)); if (multi) add(cut(data.accountName(t.accountId), 14)); add(eur(t.amount))
        }, tones = mapOf(cols.lastIndex to if (t.amount.signum() > 0 && t.kind == TxKind.ENTRATA) Tone.POSITIVE else Tone.NORMAL))
        fun subtotal(label: String, l: List<Transaction>) = TRow(buildList { add(""); add(label); add(""); if (multi) add(""); add(eur(l.filter { it.kind != TxKind.TRASFERIMENTO }.fold(BigDecimal.ZERO) { a, t -> a + t.amount })) }, bold = true)
        val inc = list.filter { it.kind == TxKind.ENTRATA }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }
        val exp = list.filter { it.kind == TxKind.SPESA }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }
        val footer = TRow(buildList { add(""); add("Entrate ${eur0(inc)} · Spese ${eur0(exp.negate())}"); add(""); if (multi) add(""); add(eur(inc + exp)) }, bold = true)
        when (c.spec.grouping) {
            TxGrouping.NONE -> out += Block.Table(null, cols, list.map(::row), footer)
            else -> {
                val groups: List<Pair<String, List<Transaction>>> = if (c.spec.grouping == TxGrouping.CATEGORY)
                    list.groupBy { data.categoryName(it.categoryId) }.entries.sortedBy { it.key }.map { it.key to it.value }
                else list.groupBy { YearMonth.from(it.bookingDate) }.entries.sortedBy { it.key }.map { Dates.monthLabelLong(it.key) to it.value }
                val rows = ArrayList<TRow>()
                groups.forEach { (name, l) -> rows += TRow(listOf(name), group = true); l.forEach { rows += row(it) }; rows += subtotal("Subtotale $name", l) }
                out += Block.Table(null, cols, rows, footer)
            }
        }
        if (list.any { it.origin == TxOrigin.NOTIFICATION }) out += Block.Paragraph("* movimento provvisorio da notifica, in attesa dell'estratto conto.", Tone.MUTED)
    }
}
