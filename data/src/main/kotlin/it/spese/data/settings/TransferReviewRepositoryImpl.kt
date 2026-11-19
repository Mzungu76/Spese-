package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.classification.TransferReviewRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.transferReviewStore: DataStore<Preferences> by preferencesDataStore("transfer_review")

@Singleton
class TransferReviewRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : TransferReviewRepository {
    private val key = stringSetPreferencesKey("ignored")
    override fun observeIgnored(): Flow<Set<String>> = context.transferReviewStore.data.map { it[key] ?: emptySet() }
    override suspend fun getIgnored(): Set<String> = observeIgnored().first()
    override suspend fun ignore(key: String) { context.transferReviewStore.edit { it[this.key] = (it[this.key] ?: emptySet()) + key } }
    override suspend fun setIgnored(keys: Set<String>) { context.transferReviewStore.edit { it[key] = keys } }
}
