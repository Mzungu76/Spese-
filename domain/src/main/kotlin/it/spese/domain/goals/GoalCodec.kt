package it.spese.domain.goals

import it.spese.core.model.Goal
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Salvataggio degli obiettivi come testo JSON (preferenze dell'app): importi come stringhe, data come giorno dell'epoca. */
object GoalCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Dto(val id: Long, val name: String, val target: String, val saved: String = "0", val deadline: Long? = null, val monthly: String? = null, val priority: Int = 0, val enabled: Boolean = true)

    fun encode(goals: List<Goal>): String = json.encodeToString(goals.map {
        Dto(it.id, it.name, it.target.toPlainString(), it.saved.toPlainString(), it.deadline?.toEpochDay(), it.monthly?.toPlainString(), it.priority, it.enabled)
    })

    fun decode(raw: String?): List<Goal> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Dto>>(raw) }.getOrDefault(emptyList()).mapNotNull { d ->
            val target = d.target.toBigDecimalOrNull() ?: return@mapNotNull null
            Goal(d.id, d.name, target, d.saved.toBigDecimalOrNull() ?: BigDecimal.ZERO, d.deadline?.let { LocalDate.ofEpochDay(it) }, d.monthly?.toBigDecimalOrNull(), d.priority, d.enabled)
        }
    }
}
