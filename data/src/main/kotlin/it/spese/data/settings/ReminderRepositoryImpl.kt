package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.reminders.Reminder
import it.spese.domain.reminders.ReminderCodec
import it.spese.domain.reminders.ReminderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.reminderStore: DataStore<Preferences> by preferencesDataStore("reminders")

/** Sveglie e promemoria: un testo JSON nelle preferenze del telefono (e nel backup). */
@Singleton
class ReminderRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : ReminderRepository {
    private val key = stringPreferencesKey("all")
    override fun observe(): Flow<List<Reminder>> = context.reminderStore.data.map { ReminderCodec.decode(it[key]) }
    override suspend fun getAll(): List<Reminder> = observe().first()
    override suspend fun save(r: Reminder): Long {
        var id = r.id
        context.reminderStore.edit { p ->
            val cur = ReminderCodec.decode(p[key])
            if (id == 0L) id = (cur.maxOfOrNull { it.id } ?: 0L) + 1
            p[key] = ReminderCodec.encode(cur.filter { it.id != id } + r.copy(id = id))
        }
        return id
    }
    override suspend fun delete(id: Long) { context.reminderStore.edit { p -> p[key] = ReminderCodec.encode(ReminderCodec.decode(p[key]).filter { it.id != id }) } }
    override suspend fun setAll(list: List<Reminder>) {
        context.reminderStore.edit { it[key] = ReminderCodec.encode(list.mapIndexed { i, r -> r.copy(id = i + 1L) }) }
    }
}
