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
        SplitEntity::class, CashQuotaEntity::class, RecurringEntity::class,
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
    abstract fun recurring(): RecurringDao

    companion object {
        const val VERSION = 10
        const val NAME = "gestione-spese.db"

        /**
         * 7 → 8: il conto di un movimento può mancare (`accountId` nullo = senza conto, in Verifica). SQLite non permette di togliere NOT NULL
         * da una colonna: si ricrea la tabella copiando tutto, e si rimettono a posto le divisioni dei movimenti (che hanno un vincolo a cascata).
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `_splits_backup` AS SELECT * FROM `transaction_splits`")
                SCHEMA_7_8_SQL.forEach { db.execSQL(it) }
                db.execSQL("DELETE FROM `transaction_splits`")
                db.execSQL("INSERT INTO `transaction_splits` SELECT * FROM `_splits_backup`")
                db.execSQL("DROP TABLE `_splits_backup`")
            }
        }

        /** 8 → 9: mese contabile scelto per un movimento (es. un'entrata di fine mese che conta per il successivo). Nessun dato esistente cambia (NULL = mese della data). */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE transactions ADD COLUMN accountingMonth TEXT") }
        }

        /**
         * 9 → 10: il risparmio mensile di partenza (300 €) del «Piano attuale» non esiste più: senza obiettivi impostati non si riserva nulla.
         * Si azzera solo il valore di default intatto (300 € al mese); un obiettivo scelto dall'utente con un altro importo resta.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("UPDATE scenarios SET goalValueCents = 0 WHERE isBase = 1 AND goalMode = 'm' AND goalValueCents = 30000") }
        }

        /** 1 → 2: icona opzionale per categoria. Nessun dato esistente cambia (NULL = default dal nome). */
        /** 6 → 7: entrate e spese ricorrenti. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { SCHEMA_6_7_SQL.forEach { db.execSQL(it) } }
        }

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
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)

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

/** Creazione della tabella dello schema 7 (identica a quella esportata in schemas/7.json). */
private val SCHEMA_6_7_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `recurring_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `accountId` INTEGER, `categoryId` INTEGER, `everyMonths` INTEGER NOT NULL, `dayOfMonth` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER, `enabled` INTEGER NOT NULL, `autoConfirm` INTEGER NOT NULL, `lastGenerated` INTEGER, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "CREATE INDEX IF NOT EXISTS `index_recurring_rules_categoryId` ON `recurring_rules` (`categoryId`)",
)


/** Ricreazione della tabella dei movimenti con il conto facoltativo (identica a quella esportata in schemas/8.json). */
private val SCHEMA_7_8_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `_new_transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `externalId` TEXT NOT NULL, `dedupKey` TEXT NOT NULL, `accountId` INTEGER, `bookingDate` INTEGER NOT NULL, `valueDate` INTEGER NOT NULL, `description` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `kind` TEXT NOT NULL, `categoryId` INTEGER, `manualCategory` INTEGER NOT NULL, `manualUpdatedAt` INTEGER NOT NULL, `ruleId` INTEGER, `merchantOverride` TEXT, `notes` TEXT NOT NULL, `origin` TEXT NOT NULL, `documentId` INTEGER, `importBatchId` INTEGER, `createdAt` INTEGER NOT NULL, `reviewed` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`importBatchId`) REFERENCES `import_batches`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "INSERT INTO `_new_transactions` (`id`, `externalId`, `dedupKey`, `accountId`, `bookingDate`, `valueDate`, `description`, `amountCents`, `kind`, `categoryId`, `manualCategory`, `manualUpdatedAt`, `ruleId`, `merchantOverride`, `notes`, `origin`, `documentId`, `importBatchId`, `createdAt`, `reviewed`) SELECT `id`, `externalId`, `dedupKey`, `accountId`, `bookingDate`, `valueDate`, `description`, `amountCents`, `kind`, `categoryId`, `manualCategory`, `manualUpdatedAt`, `ruleId`, `merchantOverride`, `notes`, `origin`, `documentId`, `importBatchId`, `createdAt`, `reviewed` FROM `transactions`",
    "DROP TABLE `transactions`",
    "ALTER TABLE `_new_transactions` RENAME TO `transactions`",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_externalId` ON `transactions` (`externalId`)",
    "CREATE INDEX IF NOT EXISTS `index_transactions_accountId_dedupKey` ON `transactions` (`accountId`, `dedupKey`)",
    "CREATE INDEX IF NOT EXISTS `index_transactions_bookingDate` ON `transactions` (`bookingDate`)",
    "CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)",
    "CREATE INDEX IF NOT EXISTS `index_transactions_documentId` ON `transactions` (`documentId`)",
    "CREATE INDEX IF NOT EXISTS `index_transactions_importBatchId` ON `transactions` (`importBatchId`)",
)
