package it.spese.domain.networth

import it.spese.core.model.Valuation
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Valori dei conti di investimento come testo JSON (preferenze dell'app). */
object ValuationCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Dto(val account: Long, val date: Long, val value: String)

    fun encode(v: List<Valuation>): String = json.encodeToString(v.map { Dto(it.accountId, it.date.toEpochDay(), it.value.toPlainString()) })

    fun decode(raw: String?): List<Valuation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Dto>>(raw) }.getOrDefault(emptyList()).mapNotNull { d ->
            Valuation(d.account, LocalDate.ofEpochDay(d.date), d.value.toBigDecimalOrNull() ?: return@mapNotNull null)
        }
    }
}
