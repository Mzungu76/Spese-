package it.spese.domain.sharing

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.*

/** Testo JSON dei due scambi (snapshot dall'app madre, eventi dall'altro telefono). Importi come testo, date come numero di giorni. */
object ShareCodec {
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.dec(k: String) = str(k)?.let { runCatching { BigDecimal(it) }.getOrNull() }
    private fun JsonObject.day(k: String) = str(k)?.toLongOrNull()?.let { LocalDate.ofEpochDay(it) }
    private fun JsonObject.decs(k: String) = (this[k] as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content?.let { runCatching { BigDecimal(it) }.getOrNull() } } ?: emptyList()
    private fun decs(list: List<BigDecimal>) = buildJsonArray { list.forEach { add(JsonPrimitive(it.toPlainString())) } }

    fun encodeSnapshot(s: ShareSnapshot): String = buildJsonObject {
        put("v", s.version); put("at", s.generatedAtMillis); put("title", s.title); put("unit", s.unit)
        put("ps", s.periodStart.toEpochDay()); put("pe", s.periodEnd.toEpochDay()); put("today", s.today.toEpochDay()); put("days", s.days); put("idx", s.dayIndex)
        s.limit?.let { put("limit", it.toPlainString()) }; put("spent", s.spentToDate.toPlainString())
        s.optimalToDate?.let { put("optNow", it.toPlainString()) }; s.dailyRemaining?.let { put("perDay", it.toPlainString()) }; s.dailyAverage?.let { put("avgDay", it.toPlainString()) }
        put("cur", decs(s.current)); put("avg", decs(s.average)); put("opt", decs(s.optimal))
        put("capture", s.features.captureNotifications); put("chart", s.features.showChart); put("note", s.note)
        s.previousTotal?.let { put("prev", it.toPlainString()) }
        s.upcomingTotal?.let { put("upc", it.toPlainString()) }; s.reserve?.let { put("res", it.toPlainString()) }
        if (s.recent.isNotEmpty()) put("recent", buildJsonArray { s.recent.forEach { r -> add(buildJsonObject { put("d", r.day.toEpochDay()); put("t", r.description); put("a", r.amount.toPlainString()); if (r.account.isNotBlank()) put("c", r.account) }) } })
        if (s.categories.isNotEmpty()) put("cats", buildJsonArray { s.categories.forEach { c -> add(buildJsonObject { put("n", c.name); put("p", c.percent) }) } })
        if (s.thread.isNotEmpty()) put("thread", buildJsonArray { s.thread.forEach { m -> add(buildJsonObject { put("i", m.id); put("h", if (m.fromHost) 1 else 0); put("t", m.text); put("at", m.at) }) } })
    }.toString()

    /** Più periodi insieme (settimana, mese, trimestre) in un solo messaggio; i riepiloghi singoli di prima restano leggibili. */
    fun encodeBundle(list: List<ShareSnapshot>): String = buildJsonObject {
        put("v", 2); put("snaps", buildJsonArray { list.forEach { s -> add(Json.parseToJsonElement(encodeSnapshot(s))) } })
    }.toString()

    fun decodeBundle(text: String): List<ShareSnapshot> {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = o["snaps"] as? JsonArray ?: return listOfNotNull(snapshotOf(o))
        return arr.mapNotNull { e -> (e as? JsonObject)?.let { snapshotOf(it) } }
    }

    fun decodeSnapshot(text: String): ShareSnapshot? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return snapshotOf(o)
    }

    private fun snapshotOf(o: JsonObject): ShareSnapshot? {
        return ShareSnapshot(
            generatedAtMillis = o.str("at")?.toLongOrNull() ?: return null, title = o.str("title").orEmpty(), unit = o.str("unit") ?: "MONTH",
            periodStart = o.day("ps") ?: return null, periodEnd = o.day("pe") ?: return null, today = o.day("today") ?: return null,
            days = o.str("days")?.toIntOrNull() ?: return null, dayIndex = o.str("idx")?.toIntOrNull() ?: return null,
            limit = o.dec("limit"), spentToDate = o.dec("spent") ?: BigDecimal.ZERO, optimalToDate = o.dec("optNow"), dailyRemaining = o.dec("perDay"), dailyAverage = o.dec("avgDay"),
            current = o.decs("cur"), average = o.decs("avg"), optimal = o.decs("opt"),
            features = ShareFeatures(o.str("capture") != "false", o.str("chart") != "false"), note = o.str("note").orEmpty(), version = o.str("v")?.toIntOrNull() ?: 1,
            recent = (o["recent"] as? JsonArray)?.mapNotNull { e -> (e as? JsonObject)?.let { r -> ShareRecent(r.day("d") ?: return@let null, r.str("t").orEmpty(), r.dec("a") ?: return@let null, r.str("c").orEmpty()) } } ?: emptyList(),
            categories = (o["cats"] as? JsonArray)?.mapNotNull { e -> (e as? JsonObject)?.let { c -> ShareCategory(c.str("n") ?: return@let null, c.str("p")?.toIntOrNull() ?: return@let null) } } ?: emptyList(),
            previousTotal = o.dec("prev"), upcomingTotal = o.dec("upc"), reserve = o.dec("res"),
            thread = (o["thread"] as? JsonArray)?.mapNotNull { e -> (e as? JsonObject)?.let { m -> ThreadMsg(m.str("i") ?: return@let null, m.str("h") == "1", m.str("t").orEmpty(), m.str("at")?.toLongOrNull() ?: return@let null) } } ?: emptyList(),
        )
    }

    /** Un messaggio dell'altra persona alla madre (viaggia come le spese rilevate: cifrato, nella posta in arrivo). */
    fun encodeMessage(m: ThreadMsg): String = buildJsonObject { put("i", m.id); put("m", m.text); put("at", m.at) }.toString()

    /** `null` se il testo non è un messaggio (per esempio è un elenco di spese). */
    fun decodeMessage(text: String): ThreadMsg? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val t = o.str("m")?.takeIf { it.isNotBlank() } ?: return null
        return ThreadMsg(o.str("i") ?: return null, false, t.take(500), o.str("at")?.toLongOrNull() ?: return null)
    }

    fun encodeEvents(list: List<ShareEvent>): String = buildJsonArray {
        list.forEach { e -> add(buildJsonObject { put("id", e.id); put("a", e.amount.toPlainString()); put("d", e.description); put("t", e.atMillis); if (e.source.isNotBlank()) put("s", e.source) }) }
    }.toString()

    fun decodeEvents(text: String): List<ShareEvent> {
        val arr = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            ShareEvent(o.str("id") ?: return@mapNotNull null, o.dec("a") ?: return@mapNotNull null, o.str("d").orEmpty(), o.str("t")?.toLongOrNull() ?: return@mapNotNull null, o.str("s").orEmpty())
        }
    }
}
