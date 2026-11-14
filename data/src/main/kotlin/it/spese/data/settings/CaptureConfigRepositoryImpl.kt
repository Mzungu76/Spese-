package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.capture.*
import it.spese.domain.repository.CaptureConfigRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.captureStore: DataStore<Preferences> by preferencesDataStore("capture")

/** Fonti attive, conti, mittenti SMS e notifiche non riconosciute (DataStore, solo sul telefono). */
@Singleton
class CaptureConfigRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : CaptureConfigRepository {
    private companion object {
        const val REC = '\u001E'; const val FLD = '\u001F'; const val MAX_UNRECOGNIZED = 30
        val unrec = stringPreferencesKey("unrecognized")
        fun on(id: String) = booleanPreferencesKey("${id}_on")
        fun acc(id: String) = longPreferencesKey("${id}_acc")
        fun pk(id: String) = stringSetPreferencesKey("${id}_pk")
        fun snd(id: String) = stringSetPreferencesKey("${id}_snd")
        fun clean(s: String) = s.replace(REC, ' ').replace(FLD, ' ')
    }

    private fun Preferences.toConfig() = CaptureConfig(
        CaptureSource.entries.associate { s ->
            s.id to SourceConfig(this[on(s.id)] ?: false, this[acc(s.id)]?.takeIf { it > 0 }, this[pk(s.id)] ?: emptySet(), this[snd(s.id)] ?: emptySet())
        },
        (this[unrec] ?: "").split(REC).mapNotNull { e ->
            val f = e.split(FLD, limit = 3)
            if (f.size == 3) f[0].toLongOrNull()?.let { UnrecognizedNotification(it, f[1], f[2]) } else null
        },
    )

    override fun observe(): Flow<CaptureConfig> = context.captureStore.data.map { it.toConfig() }
    override suspend fun get(): CaptureConfig = observe().first()

    override suspend fun update(transform: (CaptureConfig) -> CaptureConfig) {
        context.captureStore.edit { p ->
            val n = transform(p.toConfig())
            CaptureSource.entries.forEach { s ->
                val c = n.of(s)
                p[on(s.id)] = c.enabled; p[acc(s.id)] = c.accountId ?: 0L; p[pk(s.id)] = c.extraPackages; p[snd(s.id)] = c.smsSenders
            }
            p[unrec] = n.unrecognized.sortedByDescending { it.atMs }.take(MAX_UNRECOGNIZED).joinToString(REC.toString()) { "${it.atMs}$FLD${clean(it.source)}$FLD${clean(it.text)}" }
        }
    }
}
