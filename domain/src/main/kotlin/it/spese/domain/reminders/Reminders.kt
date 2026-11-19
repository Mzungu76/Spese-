package it.spese.domain.reminders

import it.spese.core.common.Dates
import it.spese.core.model.Account
import it.spese.core.model.Transaction
import it.spese.core.model.TxOrigin
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

enum class ReminderKind(val label: String) { IMPORT("Importa estratto conto"), CUSTOM("Sveglia personale") }

enum class ReminderRepeat(val label: String) { ONCE("Una volta"), DAILY("Ogni giorno"), WEEKLY("Ogni settimana"), MONTHLY("Ogni mese") }

/**
 * Una sveglia: a ora fissa manda una notifica. Di tipo [ReminderKind.IMPORT] ricorda di importare l'estratto conto (di un conto o di tutti)
 * dicendo da che data ripartire; di tipo [ReminderKind.CUSTOM] riporta il testo scritto dall'utente.
 */
data class Reminder(
    val id: Long, val kind: ReminderKind, val title: String, val note: String = "",
    /** Solo per l'estratto conto: il conto da aggiornare (`null` = tutti i conti attivi). */
    val accountId: Long? = null,
    val repeat: ReminderRepeat = ReminderRepeat.MONTHLY,
    /** 1 = lunedì … 7 = domenica (ogni settimana). */
    val dayOfWeek: Int = 1,
    /** 1–31 (ogni mese; nei mesi più corti vale l'ultimo giorno). */
    val dayOfMonth: Int = 1,
    /** Giorno (una volta sola). */
    val date: LocalDate? = null,
    val hour: Int = 9, val minute: Int = 0,
    val enabled: Boolean = true,
)

data class ReminderMessage(val title: String, val text: String)

interface ReminderRepository {
    fun observe(): Flow<List<Reminder>>
    suspend fun getAll(): List<Reminder>
    /** Salva (id 0 = nuova) e restituisce l'id. */
    suspend fun save(r: Reminder): Long
    suspend fun delete(id: Long)
    suspend fun setAll(list: List<Reminder>)
}

object ReminderEngine {
    private fun matches(r: Reminder, day: LocalDate): Boolean = when (r.repeat) {
        ReminderRepeat.ONCE -> day == r.date
        ReminderRepeat.DAILY -> true
        ReminderRepeat.WEEKLY -> day.dayOfWeek.value == r.dayOfWeek
        ReminderRepeat.MONTHLY -> day.dayOfMonth == minOf(r.dayOfMonth.coerceIn(1, 31), day.lengthOfMonth())
    }

    /** Il prossimo istante, strettamente dopo [after], in cui la sveglia deve suonare (`null` se non ne restano). */
    fun next(r: Reminder, after: LocalDateTime): LocalDateTime? {
        val time = LocalTime.of(r.hour.coerceIn(0, 23), r.minute.coerceIn(0, 59))
        var day = after.toLocalDate()
        repeat(400) {
            if (matches(r, day)) { val at = day.atTime(time); if (at.isAfter(after)) return at }
            day = day.plusDays(1)
        }
        return null
    }

    /** Ultimo giorno coperto dai movimenti di un conto: prima quelli importati da file, altrimenti i definitivi (non provvisori). */
    fun lastCovered(accountId: Long, ledger: List<Transaction>): LocalDate? {
        val mine = ledger.filter { it.accountId == accountId }
        return mine.filter { it.origin == TxOrigin.IMPORT_FILE }.maxOfOrNull { it.bookingDate }
            ?: mine.filter { it.origin != TxOrigin.NOTIFICATION && it.origin != TxOrigin.RECURRING && it.origin != TxOrigin.OCR }.maxOfOrNull { it.bookingDate }
    }

    /** Testo della notifica. Per l'estratto conto dice, conto per conto, da che data a oggi importare. */
    fun message(r: Reminder, accounts: List<Account>, ledger: List<Transaction>, today: LocalDate): ReminderMessage {
        if (r.kind == ReminderKind.CUSTOM) return ReminderMessage(r.title.ifBlank { "Promemoria" }, r.note)
        val targets = accounts.filter { !it.archived && (r.accountId == null || it.id == r.accountId) }
        if (targets.isEmpty()) return ReminderMessage(r.title.ifBlank { "Importa l'estratto conto" }, "Il conto di questo promemoria non esiste più: aggiornalo o eliminalo.")
        val lines = targets.map { a ->
            val last = lastCovered(a.id, ledger)
            when {
                last == null -> "${a.name}: nessun movimento ancora, importa l'estratto fino al ${Dates.fmt(today)}"
                last >= today -> "${a.name}: già aggiornato a oggi"
                else -> "${a.name}: importa dal ${Dates.fmt(last)} al ${Dates.fmt(today)}"
            }
        }
        val title = r.title.ifBlank { if (targets.size == 1) "Importa l'estratto di ${targets[0].name}" else "Importa gli estratti conto" }
        return ReminderMessage(title, lines.joinToString("\n") + if (r.note.isNotBlank()) "\n" + r.note else "")
    }

    /** Descrizione breve della ripetizione, per l'elenco. */
    fun describe(r: Reminder): String {
        val t = "%02d:%02d".format(r.hour, r.minute)
        val dows = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
        return when (r.repeat) {
            ReminderRepeat.ONCE -> (r.date?.let { Dates.fmt(it) } ?: "—") + " alle $t"
            ReminderRepeat.DAILY -> "Ogni giorno alle $t"
            ReminderRepeat.WEEKLY -> "Ogni ${dows[(r.dayOfWeek - 1).coerceIn(0, 6)]} alle $t"
            ReminderRepeat.MONTHLY -> "Ogni mese il ${r.dayOfMonth} alle $t"
        }
    }
}
