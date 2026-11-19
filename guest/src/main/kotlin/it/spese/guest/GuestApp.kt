package it.spese.guest

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import it.spese.domain.sharing.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.guestStore: DataStore<Preferences> by preferencesDataStore("guest")

/** Lo stato dell'app nelle preferenze private del telefono (codice, chiave, ultimo riepilogo e spese in coda). */
class GuestStore(private val context: Context) : GuestStateRepository {
    private val key = stringPreferencesKey("state")
    override fun observe(): Flow<GuestState?> = context.guestStore.data.map { p -> GuestStateCodec.decode(p[key]) }
    override suspend fun get(): GuestState? = observe().first()
    override suspend fun set(state: GuestState?) { context.guestStore.edit { p -> if (state == null) { p.remove(key) } else { p[key] = GuestStateCodec.encode(state) } } }
    override suspend fun update(transform: (GuestState) -> GuestState) {
        context.guestStore.edit { p -> GuestStateCodec.decode(p[key])?.let { cur -> p[key] = GuestStateCodec.encode(transform(cur)) } }
    }
}

class GuestApp : Application() {
    val store by lazy { GuestStore(applicationContext) }
    val service by lazy { GuestService(store, HttpShareApi()) }
}

/** Aggiornamento periodico (circa ogni 15 minuti, il minimo di Android) e consegna rapida delle spese appena rilevate. */
object GuestScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("guest_sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GuestSyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build())
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("guest_sync") }
    fun syncSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("guest_sync_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<GuestSyncWorker>().setConstraints(online).setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES).build())
    }
}

class GuestSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GuestApp
        if (app.store.get() == null) return Result.success()
        // se la consegna non riesce, le spese restano in coda e si riprova
        val problem = app.service.sync()
        // avvisi locali (limite, riepilogo del lunedì) e widget con l'ultima cifra
        runCatching { app.service.dueAlerts(java.time.LocalDate.now()).forEach { alert -> GuestNotifier.post(applicationContext, alert) } }
        runCatching { GuestWidget.refresh(applicationContext) }
        val pending = app.store.get()?.outbox?.isNotEmpty() == true
        return if (pending && problem != null) Result.retry() else Result.success()
    }
}
