package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.health.IncomeNature
import it.spese.domain.health.IncomeNatureRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.incomeNatureStore: DataStore<Preferences> by preferencesDataStore("income_nature")

/** Correzioni alla natura delle entrate: «id categoria:F» o «id categoria:V», in un insieme di testi (solo sul telefono, nel backup). */
@Singleton
class IncomeNatureRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : IncomeNatureRepository {
    private val key = stringSetPreferencesKey("overrides")
    private fun decode(s: Set<String>?): Map<Long, IncomeNature> = (s ?: emptySet()).mapNotNull { e ->
        val id = e.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
        id to if (e.substringAfter(':') == "F") IncomeNature.FIXED else IncomeNature.VARIABLE
    }.toMap()
    private fun encode(m: Map<Long, IncomeNature>): Set<String> = m.map { (id, n) -> "$id:" + if (n == IncomeNature.FIXED) "F" else "V" }.toSet()

    override fun observe(): Flow<Map<Long, IncomeNature>> = context.incomeNatureStore.data.map { decode(it[key]) }
    override suspend fun get(): Map<Long, IncomeNature> = observe().first()
    override suspend fun set(categoryId: Long, nature: IncomeNature?) {
        context.incomeNatureStore.edit { p -> val cur = decode(p[key]).toMutableMap(); if (nature == null) cur.remove(categoryId) else cur[categoryId] = nature; p[key] = encode(cur) }
    }
    override suspend fun setAll(map: Map<Long, IncomeNature>) { context.incomeNatureStore.edit { it[key] = encode(map) } }
}
