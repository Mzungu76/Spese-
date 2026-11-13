package it.spese.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import it.spese.app.work.Notifier
import it.spese.app.work.WorkScheduler
import it.spese.data.backup.DatabaseSeeder
import it.spese.data.di.ApplicationScope
import it.spese.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class SpeseApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var seeder: DatabaseSeeder
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var scheduler: WorkScheduler
    @Inject lateinit var notifier: Notifier
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannel()
        scope.launch {
            seeder.ensureSeeded()
            scheduler.apply(settings.get())
        }
    }
}
