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
        SplitEntity::class, CashQuotaEntity::class,
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
    abstract fun splits(): SplitDao
    abstract fun quotas(): CashQuotaDao

    companion object {
        const val VERSION = 6
        const val NAME = "gestione-spese.db"

        /** 1 → 2: icona opzionale per categoria. Nessun dato esistente cambia (NULL = default dal nome). */
        /** 5 → 6: quote in contanti più flessibili (modalità, tolleranza, frequenza, finestra di giorni). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cash_quotas ADD COLUMN mode TEXT NOT NULL DEFAULT 'c'")
                db.execSQL("ALTER TABLE cash_quotas ADD COLUMN toleranceCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cash_quotas ADD COLUMN everyMonths INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE cash_quotas ADD COLUMN dayFrom INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cash_quotas ADD COLUMN dayTo INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 4 → 5: parti dei movimenti divisi, quote fisse in contanti e flag "controllato". */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 0")
                SCHEMA_4_5_SQL.forEach { db.execSQL(it) }
            }
        }

        /** 3 → 4: natura della spesa (fissa / necessaria / discrezionale). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN nature TEXT")
            }
        }

        /** 2 → 3: sottocategorie (categoria padre opzionale). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN parentId INTEGER")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT")
            }
        }

        /**
         * Migrazioni: l'elenco cresce a ogni nuova versione dello schema. Non si usa MAI
         * `fallbackToDestructiveMigration`: una migrazione mancante deve far fallire l'apertura, non cancellare i dati.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

/** Istruzioni di creazione delle tabelle dello schema 5 (identiche a quelle esportate in schemas/5.json). */
private val SCHEMA_4_5_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `transaction_splits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txId` INTEGER NOT NULL, `categoryId` INTEGER, `amountCents` INTEGER NOT NULL, `position` INTEGER NOT NULL, `auto` INTEGER NOT NULL, `quotaId` INTEGER, FOREIGN KEY(`txId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "CREATE INDEX IF NOT EXISTS `index_transaction_splits_txId` ON `transaction_splits` (`txId`)",
    "CREATE INDEX IF NOT EXISTS `index_transaction_splits_categoryId` ON `transaction_splits` (`categoryId`)",
    "CREATE TABLE IF NOT EXISTS `cash_quotas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `amountCents` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_cash_quotas_categoryId` ON `cash_quotas` (`categoryId`)",
)
