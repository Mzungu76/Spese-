package it.spese.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import it.spese.core.database.AppDatabase
import it.spese.data.backup.BackupRepositoryImpl
import it.spese.data.files.DocumentFiles
import it.spese.data.repository.*
import it.spese.data.settings.SettingsRepositoryImpl
import it.spese.domain.repository.*
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun database(@ApplicationContext c: Context): AppDatabase = AppDatabase.build(c)
    @Provides @Singleton @ApplicationScope fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun tx(i: TransactionRepositoryImpl): TransactionRepository
    @Binds abstract fun cat(i: CategoryRepositoryImpl): CategoryRepository
    @Binds abstract fun rule(i: RuleRepositoryImpl): RuleRepository
    @Binds abstract fun acc(i: AccountRepositoryImpl): AccountRepository
    @Binds abstract fun due(i: DueRepositoryImpl): DueRepository
    @Binds abstract fun scen(i: ScenarioRepositoryImpl): ScenarioRepository
    @Binds abstract fun quotas(i: CashQuotaRepositoryImpl): CashQuotaRepository
    @Binds abstract fun recurring(i: RecurringRepositoryImpl): RecurringRepository
    @Binds abstract fun valuations(i: it.spese.data.settings.ValuationRepositoryImpl): it.spese.domain.repository.ValuationRepository
    @Binds abstract fun goals(i: it.spese.data.settings.GoalRepositoryImpl): it.spese.domain.repository.GoalRepository
    @Binds abstract fun doc(i: DocumentRepositoryImpl): DocumentRepository
    @Binds abstract fun store(i: DocumentFiles): DocumentStore
    @Binds abstract fun settings(i: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun importProfiles(i: it.spese.data.settings.ImportProfileRepositoryImpl): ImportProfileRepository
    @Binds abstract fun backup(i: BackupRepositoryImpl): BackupRepository
    @Binds abstract fun reportPresets(i: it.spese.data.settings.ReportPresetRepositoryImpl): ReportPresetRepository
    @Binds abstract fun captureConfig(i: it.spese.data.settings.CaptureConfigRepositoryImpl): CaptureConfigRepository
    @Binds abstract fun subscriptionReview(i: it.spese.data.settings.SubscriptionReviewRepositoryImpl): it.spese.domain.finance.SubscriptionReviewRepository
    @Binds abstract fun itemMemory(i: it.spese.data.settings.ItemMemoryRepositoryImpl): it.spese.domain.ocr.ItemMemoryRepository
}

/** Unica sottoscrizione condivisa ai dati: tutte le schermate leggono da qui. */
@Module
@InstallIn(SingletonComponent::class)
object AppDataModule {
    @Provides @Singleton
    fun appDataSource(tx: TransactionRepository, c: CategoryRepository, a: AccountRepository, r: RuleRepository, d: DueRepository, s: ScenarioRepository, q: CashQuotaRepository, settings: SettingsRepository) =
        AppDataSource(tx, c, a, r, d, s, q, settings)

    @Provides @Singleton
    fun appData(src: AppDataSource, @ApplicationScope scope: CoroutineScope): StateFlow<AppData> = src.observe().stateIn(scope, SharingStarted.Eagerly, AppData())
}
