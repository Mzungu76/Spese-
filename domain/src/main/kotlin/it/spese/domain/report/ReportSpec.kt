package it.spese.domain.report

import it.spese.core.common.Dates
import it.spese.domain.finance.Period
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.*

/** Sezioni che un report può contenere, nell'ordine scelto dall'utente. */
enum class ReportSection(val key: String, val label: String, val hint: String) {
    SUMMARY("summary", "Riepilogo", "Entrate, spese, risparmio e confronto con il periodo precedente"),
    MONTHLY("monthly", "Andamento nel tempo", "Entrate e spese mese per mese (o settimana per periodi brevi)"),
    CATEGORIES("categories", "Categorie", "Spese ed entrate per categoria: importo, quote su spese ed entrate, media mensile, variazione"),
    CHANGES("changes", "Cosa è cambiato", "Le categorie che salgono o scendono di più rispetto al periodo precedente"),
    MERCHANTS("merchants", "Principali esercenti", "Dove si spende di più, per esercente"),
    TOP_EXPENSES("top", "Spese singole più alte", "Le spese di importo maggiore del periodo"),
    BUDGETS("budgets", "Budget e obiettivo", "Budget del mese, obiettivo di risparmio e scadenze in arrivo"),
    TRENDS("trends", "Tendenze e proiezioni", "Ogni categoria contro lo storico e proiezione a 1, 3 e 6 mesi con l'impatto sul bilancio"),
    CASH("cash", "Prelievi e movimenti divisi", "Prelievi di contante, come sono stati ripartiti e le quote fisse"),
    TRANSACTIONS("transactions", "Elenco movimenti", "Tutti i movimenti del periodo, con totali");

    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } }
}

enum class ReportPeriodKind(val key: String, val label: String) {
    CURRENT_WEEK("cw", "Settimana corrente"), CURRENT_MONTH("cm", "Mese corrente"), PREVIOUS_MONTH("pm", "Mese precedente"), MONTH("m", "Un mese a scelta"), LAST_3("l3", "Ultimi 3 mesi"), LAST_6("l6", "Ultimi 6 mesi"),
    YEAR_TO_DATE("ytd", "Quest'anno"), PREVIOUS_YEAR("py", "Anno scorso"), ALL("all", "Tutto il periodo"), CUSTOM("c", "Intervallo");

    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: CURRENT_MONTH }
}

/** Periodo di un report. "Corrente" e "precedente" sono relativi all'ultimo movimento (come nel resto dell'app). */
data class ReportPeriod(val kind: ReportPeriodKind, val month: YearMonth? = null, val from: LocalDate? = null, val to: LocalDate? = null) {
    fun resolve(dataMax: LocalDate?, cal: it.spese.core.common.MonthCalendar = it.spese.core.common.MonthCalendar.SOLAR): Period {
        val ref = dataMax ?: LocalDate.now()
        val cur = cal.monthOf(ref)
        return when (kind) {
            ReportPeriodKind.CURRENT_WEEK -> Period.CurrentWeek
            ReportPeriodKind.CURRENT_MONTH -> Period.CurrentMonth
            ReportPeriodKind.PREVIOUS_MONTH -> Period.Month(cur.minusMonths(1))
            ReportPeriodKind.MONTH -> Period.Month(month ?: cur)
            ReportPeriodKind.LAST_3 -> Period.Last3Months
            ReportPeriodKind.LAST_6 -> Period.Last6Months
            ReportPeriodKind.YEAR_TO_DATE -> Period.YearToDate
            ReportPeriodKind.PREVIOUS_YEAR -> Period.Custom(cal.start(YearMonth.of(cur.year - 1, 1)), cal.end(YearMonth.of(cur.year - 1, 12)))
            ReportPeriodKind.ALL -> Period.All
            ReportPeriodKind.CUSTOM -> Period.Custom(from, to)
        }
    }

    fun label(): String = when (kind) { ReportPeriodKind.MONTH -> month?.let { Dates.monthLabelLong(it) } ?: kind.label
        ReportPeriodKind.CUSTOM -> listOfNotNull(from?.let { Dates.fmt(it) }, to?.let { Dates.fmt(it) }).joinToString(" – ").ifBlank { kind.label }
        else -> kind.label }
}

enum class TxGrouping(val key: String, val label: String) {
    NONE("n", "Per data"), CATEGORY("c", "Per categoria"), MONTH("m", "Per mese");

    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: NONE }
}

/** Tutto ciò che si può scegliere per un report. */
data class ReportSpec(
    /** Titolo personalizzato (vuoto = quello del modello). */
    val title: String = "",
    /** Testo libero stampato sotto il titolo. */
    val note: String = "",
    val period: ReportPeriod = ReportPeriod(ReportPeriodKind.CURRENT_MONTH),
    val accountId: Long? = null,
    /** Categorie a cui limitare categorie ed elenco movimenti (vuoto = tutte). */
    val categoryIds: Set<Long> = emptySet(),
    val sections: List<ReportSection> = listOf(ReportSection.SUMMARY, ReportSection.CATEGORIES),
    val charts: Boolean = true,
    val subcategories: Boolean = true,
    /** Quante righe nei primi elenchi (esercenti, spese più alte, cambiamenti). */
    val topN: Int = 10,
    val landscape: Boolean = false,
    val grouping: TxGrouping = TxGrouping.NONE,
    val includeTransfers: Boolean = false,
)

data class ReportPreset(val id: String, val name: String, val description: String, val spec: ReportSpec)

/** Modelli pronti: si scelgono così come sono o si personalizzano prima di creare il PDF. */
object ReportPresets {
    private fun p(kind: ReportPeriodKind) = ReportPeriod(kind)
    val BUILT_IN: List<ReportPreset> = listOf(
        ReportPreset("weekly", "Riepilogo settimanale", "La settimana in corso: entrate, spese, categorie, esercenti e spese più alte.",
            ReportSpec("Riepilogo settimanale", period = p(ReportPeriodKind.CURRENT_WEEK), sections = listOf(ReportSection.SUMMARY, ReportSection.CATEGORIES, ReportSection.MERCHANTS, ReportSection.TOP_EXPENSES), topN = 8)),
        ReportPreset("monthly", "Riepilogo mensile", "Il mese in una pagina: entrate, spese, categorie, esercenti, spese più alte e budget.",
            ReportSpec("Riepilogo mensile", period = p(ReportPeriodKind.CURRENT_MONTH), sections = listOf(ReportSection.SUMMARY, ReportSection.CATEGORIES, ReportSection.MERCHANTS, ReportSection.TOP_EXPENSES, ReportSection.BUDGETS), topN = 8)),
        ReportPreset("yearly", "Riepilogo annuale", "L'anno mese per mese, con le categorie e gli esercenti principali.",
            ReportSpec("Riepilogo annuale", period = p(ReportPeriodKind.YEAR_TO_DATE), sections = listOf(ReportSection.SUMMARY, ReportSection.MONTHLY, ReportSection.CATEGORIES, ReportSection.MERCHANTS), topN = 12)),
        ReportPreset("categories", "Spese per categoria", "Dove vanno i soldi negli ultimi 3 mesi, con quote, medie e variazioni.",
            ReportSpec("Spese per categoria", period = p(ReportPeriodKind.LAST_3), sections = listOf(ReportSection.SUMMARY, ReportSection.CATEGORIES, ReportSection.CHANGES), topN = 10)),
        ReportPreset("trends", "Tendenze e proiezioni", "Ogni categoria contro lo storico e cosa succede a 1, 3 e 6 mesi se la tendenza continua.",
            ReportSpec("Tendenze e proiezioni", period = p(ReportPeriodKind.LAST_3), sections = listOf(ReportSection.SUMMARY, ReportSection.TRENDS), landscape = true)),
        ReportPreset("budgets", "Budget e obiettivi", "Budget del mese, obiettivo di risparmio e scadenze in arrivo.",
            ReportSpec("Budget e obiettivi", period = p(ReportPeriodKind.CURRENT_MONTH), sections = listOf(ReportSection.SUMMARY, ReportSection.BUDGETS))),
        ReportPreset("transactions", "Elenco movimenti", "Tutti i movimenti del mese, raggruppati per categoria, con i subtotali.",
            ReportSpec("Elenco movimenti", period = p(ReportPeriodKind.CURRENT_MONTH), sections = listOf(ReportSection.TRANSACTIONS), grouping = TxGrouping.CATEGORY, charts = false)),
        ReportPreset("cash", "Contanti e divisioni", "I prelievi dell'anno e come sono stati ripartiti (affitto e altre quote).",
            ReportSpec("Contanti e divisioni", period = p(ReportPeriodKind.YEAR_TO_DATE), sections = listOf(ReportSection.CASH))),
        ReportPreset("accountant", "Per il commercialista", "Quadro dell'anno scorso: riepilogo, categorie e tutti i movimenti, senza grafici.",
            ReportSpec("Spese e entrate dell'anno", period = p(ReportPeriodKind.PREVIOUS_YEAR), sections = listOf(ReportSection.SUMMARY, ReportSection.CATEGORIES, ReportSection.TRANSACTIONS), charts = false, grouping = TxGrouping.MONTH, includeTransfers = true)),
    )

    fun byId(id: String) = BUILT_IN.firstOrNull { it.id == id }
}

/** Un modello personalizzato salvato dall'utente. */
data class SavedReport(val id: String, val name: String, val spec: ReportSpec)

/** Serializzazione dei modelli salvati (JSON, solo sul telefono). */
object ReportSpecCodec {
    fun toJson(s: ReportSpec): JsonObject = buildJsonObject {
        put("title", s.title); put("note", s.note)
        put("period", buildJsonObject {
            put("kind", s.period.kind.key); s.period.month?.let { put("month", it.toString()) }; s.period.from?.let { put("from", it.toString()) }; s.period.to?.let { put("to", it.toString()) }
        })
        s.accountId?.let { put("account", it) }
        put("categories", buildJsonArray { s.categoryIds.sorted().forEach { add(JsonPrimitive(it)) } })
        put("sections", buildJsonArray { s.sections.forEach { add(JsonPrimitive(it.key)) } })
        put("charts", s.charts); put("sub", s.subcategories); put("topN", s.topN); put("landscape", s.landscape); put("grouping", s.grouping.key); put("transfers", s.includeTransfers)
    }

    fun fromJson(o: JsonObject): ReportSpec {
        val p = o["period"]?.jsonObject
        val period = ReportPeriod(ReportPeriodKind.fromKey(p?.get("kind")?.jsonPrimitive?.contentOrNull),
            p?.get("month")?.jsonPrimitive?.contentOrNull?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
            p?.get("from")?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            p?.get("to")?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
        val sections = o["sections"]?.jsonArray?.mapNotNull { ReportSection.fromKey(it.jsonPrimitive.contentOrNull) }.orEmpty().ifEmpty { listOf(ReportSection.SUMMARY) }
        return ReportSpec(
            o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["note"]?.jsonPrimitive?.contentOrNull.orEmpty(), period,
            o["account"]?.jsonPrimitive?.longOrNull, o["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }?.toSet().orEmpty(), sections,
            o["charts"]?.jsonPrimitive?.booleanOrNull ?: true, o["sub"]?.jsonPrimitive?.booleanOrNull ?: true, (o["topN"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(3, 50),
            o["landscape"]?.jsonPrimitive?.booleanOrNull ?: false, TxGrouping.fromKey(o["grouping"]?.jsonPrimitive?.contentOrNull), o["transfers"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    fun encodeList(list: List<SavedReport>): String = buildJsonArray { list.forEach { r -> add(buildJsonObject { put("id", r.id); put("name", r.name); put("spec", toJson(r.spec)) }) } }.toString()

    fun decodeList(text: String?): List<SavedReport> = runCatching {
        if (text.isNullOrBlank()) emptyList() else Json.parseToJsonElement(text).jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SavedReport(id, o["name"]?.jsonPrimitive?.contentOrNull ?: "Report", fromJson(o["spec"]?.jsonObject ?: return@mapNotNull null))
        }
    }.getOrDefault(emptyList())
}
