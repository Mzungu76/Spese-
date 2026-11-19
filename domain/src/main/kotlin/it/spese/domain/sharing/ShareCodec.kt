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
    }.toString()

    fun decodeSnapshot(text: String): ShareSnapshot? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return ShareSnapshot(
            generatedAtMillis = o.str("at")?.toLongOrNull() ?: return null, title = o.str("title").orEmpty(), unit = o.str("unit") ?: "MONTH",
            periodStart = o.day("ps") ?: return null, periodEnd = o.day("pe") ?: return null, today = o.day("today") ?: return null,
            days = o.str("days")?.toIntOrNull() ?: return null, dayIndex = o.str("idx")?.toIntOrNull() ?: return null,
            limit = o.dec("limit"), spentToDate = o.dec("spent") ?: BigDecimal.ZERO, optimalToDate = o.dec("optNow"), dailyRemaining = o.dec("perDay"), dailyAverage = o.dec("avgDay"),
            current = o.decs("cur"), average = o.decs("avg"), optimal = o.decs("opt"),
            features = ShareFeatures(o.str("capture") != "false", o.str("chart") != "false"), note = o.str("note").orEmpty(), version = o.str("v")?.toIntOrNull() ?: 1,
        )
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
