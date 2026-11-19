package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.core.model.Valuation
import it.spese.domain.networth.ValuationCodec
import it.spese.domain.repository.ValuationRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.valuationStore: DataStore<Preferences> by preferencesDataStore("valuations")

/** Valori dei conti di investimento inseriti dall'utente, sul telefono insieme alle altre preferenze (e nel backup). */
@Singleton
class ValuationRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : ValuationRepository {
    private val key = stringPreferencesKey("values")
    override fun observeAll(): Flow<List<Valuation>> = context.valuationStore.data.map { ValuationCodec.decode(it[key]) }
    override suspend fun getAll(): List<Valuation> = observeAll().first()

    override suspend fun save(v: Valuation) {
        context.valuationStore.edit { p ->
            val cur = ValuationCodec.decode(p[key]).filter { !(it.accountId == v.accountId && it.date == v.date) }
            p[key] = ValuationCodec.encode(cur + v)
        }
    }

    override suspend fun delete(accountId: Long, date: LocalDate) {
        context.valuationStore.edit { p -> p[key] = ValuationCodec.encode(ValuationCodec.decode(p[key]).filter { !(it.accountId == accountId && it.date == date) }) }
    }

    override suspend fun setAll(values: List<Valuation>) { context.valuationStore.edit { p -> p[key] = ValuationCodec.encode(values) } }
}
