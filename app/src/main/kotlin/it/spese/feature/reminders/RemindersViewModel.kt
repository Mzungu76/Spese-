package it.spese.feature.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.app.work.Notifier
import it.spese.app.work.WorkScheduler
import it.spese.domain.reminders.Reminder
import it.spese.domain.reminders.ReminderEngine
import it.spese.domain.reminders.ReminderRepository
import it.spese.domain.repository.AppData
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RemindersViewModel @Inject constructor(
    val data: StateFlow<AppData>, private val repo: ReminderRepository, private val scheduler: WorkScheduler, private val notifier: Notifier, private val messenger: UiMessenger,
) : ViewModel() {
    val reminders: StateFlow<List<Reminder>> = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val canNotify: Boolean get() = notifier.canPost()

    fun save(r: Reminder) = viewModelScope.launch {
        val id = repo.save(r)
        scheduler.scheduleReminder(r.copy(id = id))
        val next = if (r.enabled) ReminderEngine.next(r, java.time.LocalDateTime.now()) else null
        messenger.show(if (r.enabled && next == null) "Questa sveglia è già passata: scegli una data futura" else "Salvata")
    }
    fun setEnabled(r: Reminder, on: Boolean) = save(r.copy(enabled = on))
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id); scheduler.cancelReminder(id); messenger.show("Sveglia eliminata") }
    /** Manda subito la notifica, per vedere com'è fatta. */
    fun test(r: Reminder) {
        val d = data.value
        val m = ReminderEngine.message(r, d.accounts, d.ledger, LocalDate.now())
        if (!notifier.canPost()) { messenger.show("Le notifiche dell'app sono disattivate: attivale dalle impostazioni del telefono"); return }
        notifier.post(2999, m.title, m.text.lineSequence().firstOrNull().orEmpty().ifBlank { m.title }, m.text.ifBlank { null }, if (r.kind == it.spese.domain.reminders.ReminderKind.IMPORT) it.spese.app.navigation.Routes.IMPORT else null)
    }
}
