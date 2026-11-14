package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.repository.AppSettings
import it.spese.domain.repository.SettingsRepository
import it.spese.domain.repository.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

/** Preferenze dell'utente (DataStore). Nessun dato finanziario vi è memorizzato. */
@Singleton
class SettingsRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : SettingsRepository {
    private object K {
        val theme = stringPreferencesKey("theme"); val notif = booleanPreferencesKey("notif")
        val nBud = booleanPreferencesKey("n_bud"); val nDue = booleanPreferencesKey("n_due")
        val nRec = booleanPreferencesKey("n_rec"); val nUnx = booleanPreferencesKey("n_unx"); val hour = intPreferencesKey("n_hour")
        val ab = booleanPreferencesKey("ab"); val abDays = intPreferencesKey("ab_days"); val abKeep = intPreferencesKey("ab_keep")
        val abLast = longPreferencesKey("ab_last"); val onb = booleanPreferencesKey("onb"); val undo = stringPreferencesKey("undo")
        val syncUri = stringPreferencesKey("sync_uri"); val syncAt = longPreferencesKey("sync_at")
    }

    private fun Preferences.toSettings() = AppSettings(
        runCatching { ThemeMode.valueOf(this[K.theme] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
        this[K.notif] ?: false, this[K.nBud] ?: true, this[K.nDue] ?: true, this[K.nRec] ?: true, this[K.nUnx] ?: true, this[K.hour] ?: 9,
        this[K.ab] ?: false, this[K.abDays] ?: 7, this[K.abKeep] ?: 5, this[K.abLast] ?: 0, this[K.onb] ?: false, this[K.undo] ?: "", this[K.syncUri] ?: "", this[K.syncAt] ?: 0,
    )

    override fun observe(): Flow<AppSettings> = context.store.data.map { it.toSettings() }
    override suspend fun get(): AppSettings = observe().first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.store.edit { p ->
            val n = transform(p.toSettings())
            p[K.theme] = n.themeMode.name; p[K.notif] = n.notificationsEnabled; p[K.nBud] = n.notifyBudgets; p[K.nDue] = n.notifyDues
            p[K.nRec] = n.notifyRecurring; p[K.nUnx] = n.notifyUnexpected; p[K.hour] = n.notifyHour; p[K.ab] = n.autoBackupEnabled
            p[K.abDays] = n.autoBackupEveryDays; p[K.abKeep] = n.autoBackupKeep; p[K.abLast] = n.lastAutoBackupAt; p[K.onb] = n.onboardingDone; p[K.undo] = n.lastUndo; p[K.syncUri] = n.syncUri; p[K.syncAt] = n.lastSyncAt
        }
    }
}
