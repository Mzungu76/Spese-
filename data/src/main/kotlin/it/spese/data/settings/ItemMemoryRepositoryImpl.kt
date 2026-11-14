package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.ocr.ItemMemory
import it.spese.domain.ocr.ItemMemoryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.itemMemoryStore: DataStore<Preferences> by preferencesDataStore("item_memory")

@Singleton
class ItemMemoryRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : ItemMemoryRepository {
    private val key = stringPreferencesKey("entries")
    private fun decode(s: String?): Map<String, Long> = (s ?: "").lineSequence().mapNotNull { l ->
        val i = l.lastIndexOf('\t'); if (i <= 0) null else l.substring(i + 1).toLongOrNull()?.let { l.substring(0, i) to it }
    }.toMap(LinkedHashMap())

    override suspend fun get(): Map<String, Long> = decode(context.itemMemoryStore.data.first()[key])
    override suspend fun learn(entries: Map<String, Long>) {
        if (entries.isEmpty()) return
        context.itemMemoryStore.edit { p ->
            val merged = ItemMemory.merge(decode(p[key]), entries)
            p[key] = merged.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        }
    }
    override suspend fun clear() { context.itemMemoryStore.edit { it.remove(key) } }
}
