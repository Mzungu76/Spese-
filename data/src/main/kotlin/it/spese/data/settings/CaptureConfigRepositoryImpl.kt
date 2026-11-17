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
        val unrec = stringPreferencesKey("unrecognized"); val ign = stringPreferencesKey("ignore_rules"); val pend = stringPreferencesKey("pending_amounts"); val seen = stringPreferencesKey("last_seen"); val noAmt = booleanPreferencesKey("capture_no_amount"); val notify = booleanPreferencesKey("notify_on_add")
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
        this[notify] ?: true,
        (this[ign] ?: "").split(REC).mapNotNull { e ->
            val f = e.split(FLD)
            if (f.size >= 4) f[2].toLongOrNull()?.let { IgnoreRule(f[0], f[1], it, f[3] != "0") } else null
        },
        (this[pend] ?: "").split(REC).mapNotNull { e ->
            val f = e.split(FLD, limit = 6)
            if (f.size == 6) f[0].toLongOrNull()?.let { id -> PendingAmount(id, f[1], f[2].toLongOrNull()?.takeIf { it > 0 }, (f[3].toIntOrNull() ?: -1).coerceIn(-1, 1), f[4], f[5]) } else null
        },
        (this[seen] ?: "").split(';').mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { f -> f[1].toLongOrNull()?.let { f[0] to it } } }.toMap(),
        this[noAmt] ?: true,
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
            p[notify] = n.notifyOnAdd
            p[noAmt] = n.captureNoAmount
            p[seen] = n.lastSeen.entries.joinToString(";") { "${it.key}:${it.value}" }
            p[pend] = n.pending.sortedByDescending { it.id }.take(50).joinToString(REC.toString()) { "${it.id}$FLD${clean(it.sourceId)}$FLD${it.accountId ?: 0L}$FLD${it.sign}$FLD${clean(it.description)}$FLD${clean(it.text)}" }
            p[ign] = n.ignoreRules.joinToString(REC.toString()) { "${clean(it.source)}$FLD${clean(it.phrase)}$FLD${it.createdMs}$FLD${if (it.enabled) 1 else 0}" }
            p[unrec] = n.unrecognized.sortedByDescending { it.atMs }.take(MAX_UNRECOGNIZED).joinToString(REC.toString()) { "${it.atMs}$FLD${clean(it.source)}$FLD${clean(it.text)}" }
        }
    }
}
