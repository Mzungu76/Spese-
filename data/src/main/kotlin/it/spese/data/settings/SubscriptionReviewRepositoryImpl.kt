package it.spese.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import it.spese.domain.finance.SubscriptionReviewRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.subscriptionStore: DataStore<Preferences> by preferencesDataStore("subscriptions")

@Singleton
class SubscriptionReviewRepositoryImpl @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) : SubscriptionReviewRepository {
    private val key = stringSetPreferencesKey("ignored")
    override fun observeIgnored(): Flow<Set<String>> = context.subscriptionStore.data.map { it[key] ?: emptySet() }
    override suspend fun getIgnored(): Set<String> = observeIgnored().first()
    override suspend fun ignore(key: String) { context.subscriptionStore.edit { it[this.key] = (it[this.key] ?: emptySet()) + key } }
    override suspend fun restore(key: String) { context.subscriptionStore.edit { it[this.key] = (it[this.key] ?: emptySet()) - key } }
    override suspend fun setIgnored(keys: Set<String>) { context.subscriptionStore.edit { it[key] = keys } }
}
