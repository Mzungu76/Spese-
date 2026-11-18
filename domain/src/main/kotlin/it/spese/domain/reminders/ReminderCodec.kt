package it.spese.domain.reminders

import java.time.LocalDate
import kotlinx.serialization.json.*

/** Salvataggio delle sveglie come testo JSON (nelle preferenze del telefono). */
object ReminderCodec {
    fun encode(list: List<Reminder>): String = buildJsonArray {
        list.forEach { r -> add(buildJsonObject {
            put("id", r.id); put("k", r.kind.name); put("t", r.title); put("n", r.note); r.accountId?.let { put("a", it) }
            put("r", r.repeat.name); put("w", r.dayOfWeek); put("m", r.dayOfMonth); r.date?.let { put("d", it.toEpochDay()) }
            put("h", r.hour); put("mi", r.minute); put("on", r.enabled)
        }) }
    }.toString()

    fun decode(s: String?): List<Reminder> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(s) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val id = str("id")?.toLongOrNull() ?: return@mapNotNull null
            Reminder(
                id, runCatching { ReminderKind.valueOf(str("k") ?: "") }.getOrDefault(ReminderKind.CUSTOM), str("t") ?: "", str("n") ?: "", str("a")?.toLongOrNull(),
                runCatching { ReminderRepeat.valueOf(str("r") ?: "") }.getOrDefault(ReminderRepeat.MONTHLY),
                (str("w")?.toIntOrNull() ?: 1).coerceIn(1, 7), (str("m")?.toIntOrNull() ?: 1).coerceIn(1, 31), str("d")?.toLongOrNull()?.let { LocalDate.ofEpochDay(it) },
                (str("h")?.toIntOrNull() ?: 9).coerceIn(0, 23), (str("mi")?.toIntOrNull() ?: 0).coerceIn(0, 59), str("on") != "false",
            )
        }
    }
}
