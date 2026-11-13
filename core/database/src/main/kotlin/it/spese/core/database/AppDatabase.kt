package it.spese.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import it.spese.core.database.dao.*
import it.spese.core.database.entity.*

@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, RuleEntity::class, TransactionEntity::class, ImportBatchEntity::class,
        DocumentEntity::class, DueEntity::class, ScenarioEntity::class, ScenarioCutEntity::class, ScenarioRemovedEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun rules(): RuleDao
    abstract fun transactions(): TransactionDao
    abstract fun importBatches(): ImportBatchDao
    abstract fun documents(): DocumentDao
    abstract fun dues(): DueDao
    abstract fun scenarios(): ScenarioDao

    companion object {
        const val VERSION = 1
        const val NAME = "gestione-spese.db"

        /**
         * Migrazioni: l'elenco cresce a ogni nuova versione dello schema. Non si usa MAI
         * `fallbackToDestructiveMigration`: una migrazione mancante deve far fallire l'apertura, non cancellare i dati.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
