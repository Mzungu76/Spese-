package it.spese.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Garanzie sulle migrazioni dello schema: nessun dato si perde nel passaggio tra versioni.
 *
 * - Ogni versione dello schema è esportata in `schemas/` (obbligatorio, committata nel repository).
 * - Il test applica TUTTE le migrazioni dichiarate in [AppDatabase.MIGRATIONS] a partire da ogni versione storica,
 *   inserendo dati reali nella versione di partenza e verificando che ci siano ancora dopo la migrazione,
 *   e che Room valuti lo schema finale identico a quello atteso.
 * - Non è mai ammesso `fallbackToDestructiveMigration`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun `ogni versione dello schema ha una migrazione verso la successiva`() {
        val needed = (1 until AppDatabase.VERSION).map { it to it + 1 }
        val declared = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertTrue("Mancano migrazioni: ${needed - declared.toSet()}", declared.containsAll(needed))
    }

    @Test fun `lo schema 1 viene creato e validato dallo schema esportato`() {
        helper.createDatabase("test-v1", 1).apply {
            execSQL("INSERT INTO accounts (name, type, openingCents, archived) VALUES ('Intesa', 'CONTO', 0, 0)")
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt) VALUES ('Altro', 'spesa', 1, 4286611584, 1, 0, 0, 0)")
            execSQL(
                "INSERT INTO transactions (externalId, dedupKey, accountId, bookingDate, valueDate, description, amountCents, kind, categoryId, manualCategory, manualUpdatedAt, notes, origin, createdAt) " +
                    "VALUES ('e1', 'k1', 1, 19800, 19800, 'Caffè', -130, 'spesa', 1, 0, 0, '', 'MANUAL', 0)",
            )
            close()
        }
        // apre la stessa base alla versione corrente applicando tutte le migrazioni dichiarate e la valida
        val db = helper.runMigrationsAndValidate("test-v1", AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT amountCents, description FROM transactions").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(-130L, c.getLong(0)); assertEquals("Caffè", c.getString(1))
        }
        db.close()
    }

    @Test fun `la chiave esterna sul conto impedisce di eliminare un conto con movimenti`() {
        val db = helper.createDatabase("test-fk", 1)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO accounts (name, type, openingCents, archived) VALUES ('A', 'CONTO', 0, 0)")
        db.execSQL("INSERT INTO transactions (externalId, dedupKey, accountId, bookingDate, valueDate, description, amountCents, kind, manualCategory, manualUpdatedAt, notes, origin, createdAt) VALUES ('e', 'k', 1, 1, 1, 'x', 1, 'spesa', 0, 0, '', 'MANUAL', 0)")
        val failed = try { db.execSQL("DELETE FROM accounts WHERE id = 1"); false } catch (e: Exception) { true }
        assertTrue("RESTRICT deve impedire l'eliminazione", failed)
        db.close()
    }

    @Test fun `la migrazione 1-2 aggiunge l'icona (NULL) senza toccare le categorie esistenti`() {
        helper.createDatabase("test-icon", 1).apply {
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt) VALUES ('Bar', 'spesa', 1, 4286611584, 1, 3, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-icon", 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT name, sortOrder, icon FROM categories").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Bar", c.getString(0)); assertEquals(3, c.getInt(1)); assertTrue(c.isNull(2))
        }
        db.execSQL("UPDATE categories SET icon = 'coffee' WHERE name = 'Bar'")
        db.query("SELECT icon FROM categories").use { c -> assertTrue(c.moveToFirst()); assertEquals("coffee", c.getString(0)) }
        db.close()
    }

    @Test fun `la migrazione 2-3 aggiunge il padre (NULL) e conserva icone e budget`() {
        helper.createDatabase("test-parent", 2).apply {
            execSQL("INSERT INTO categories (name, kind, flexible, color, budgetCents, active, sortOrder, updatedAt, budgetUpdatedAt, icon) VALUES ('Casa', 'spesa', 0, 4286611584, 30000, 1, 0, 0, 0, 'home')")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-parent", 3, true, AppDatabase.MIGRATION_2_3)
        db.query("SELECT name, budgetCents, icon, parentId FROM categories").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Casa", c.getString(0)); assertEquals(30000L, c.getLong(1)); assertEquals("home", c.getString(2)); assertTrue(c.isNull(3))
        }
        db.close()
    }

    @Test fun `tutte le migrazioni in sequenza dal primo schema fino all'ultimo`() {
        helper.createDatabase("test-chain", 1).apply {
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt) VALUES ('Bar', 'spesa', 1, 4286611584, 1, 0, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-chain", AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT COUNT(*) FROM categories").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        db.close()
    }

    @Test fun `la migrazione 3-4 aggiunge la natura (NULL) e conserva sottocategorie e icone`() {
        helper.createDatabase("test-nature", 3).apply {
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt, icon, parentId) VALUES ('Casa', 'spesa', 0, 4286611584, 1, 0, 0, 0, 'home', NULL)")
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt, icon, parentId) VALUES ('Bollette', 'spesa', 0, 4286611584, 1, 1, 0, 0, NULL, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-nature", 4, true, AppDatabase.MIGRATION_3_4)
        db.query("SELECT name, parentId, nature FROM categories ORDER BY id").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Casa", c.getString(0)); assertTrue(c.isNull(2))
            assertTrue(c.moveToNext()); assertEquals(1L, c.getLong(1)); assertTrue(c.isNull(2))
        }
        db.close()
    }

    @Test fun `la migrazione 4-5 aggiunge parti, quote e il flag controllato, senza toccare i movimenti`() {
        helper.createDatabase("test-splits", 4).apply {
            execSQL("INSERT INTO accounts (name, type, openingCents, archived) VALUES ('Intesa', 'CONTO', 0, 0)")
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt) VALUES ('Contanti', 'spesa', 1, 4286611584, 1, 0, 0, 0)")
            execSQL("INSERT INTO transactions (externalId, dedupKey, accountId, bookingDate, valueDate, description, amountCents, kind, categoryId, manualCategory, manualUpdatedAt, notes, origin, createdAt) VALUES ('e1', 'k1', 1, 19800, 19800, 'Prelievo', -80000, 'spesa', 1, 0, 0, '', 'IMPORT_FILE', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-splits", 5, true, AppDatabase.MIGRATION_4_5)
        db.query("SELECT amountCents, reviewed FROM transactions").use { c -> assertTrue(c.moveToFirst()); assertEquals(-80000L, c.getLong(0)); assertEquals(0, c.getInt(1)) }
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO transaction_splits (txId, categoryId, amountCents, position, auto, quotaId) VALUES (1, 1, -50000, 0, 1, NULL)")
        db.execSQL("INSERT INTO cash_quotas (name, categoryId, amountCents, enabled) VALUES ('Affitto', 1, 50000, 1)")
        db.execSQL("DELETE FROM transactions WHERE id = 1")
        db.query("SELECT COUNT(*) FROM transaction_splits").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }   // le parti seguono il movimento
        db.close()
    }

    @Test fun `la migrazione 5-6 aggiunge le opzioni delle quote conservando quelle esistenti`() {
        helper.createDatabase("test-quota", 5).apply {
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt) VALUES ('Affitto', 'spesa', 0, 4286611584, 1, 0, 0, 0)")
            execSQL("INSERT INTO cash_quotas (name, categoryId, amountCents, enabled) VALUES ('Affitto', 1, 50000, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-quota", 6, true, AppDatabase.MIGRATION_5_6)
        db.query("SELECT name, amountCents, mode, toleranceCents, everyMonths, dayFrom, dayTo FROM cash_quotas").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Affitto", c.getString(0)); assertEquals(50000L, c.getLong(1))
            assertEquals("c", c.getString(2)); assertEquals(0L, c.getLong(3)); assertEquals(1, c.getInt(4)); assertEquals(0, c.getInt(5)); assertEquals(0, c.getInt(6))
        }
        db.close()
    }

    @Test fun `la migrazione 6-7 aggiunge le regole ricorrenti senza toccare i dati`() {
        helper.createDatabase("test-recurring", 6).apply {
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt) VALUES ('Stipendio', 'entrata', 0, 4286611584, 1, 0, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-recurring", 7, true, AppDatabase.MIGRATION_6_7)
        db.execSQL("INSERT INTO recurring_rules (name, kind, amountCents, accountId, categoryId, everyMonths, dayOfMonth, startDate, endDate, enabled, autoConfirm, lastGenerated) VALUES ('Stipendio', 'entrata', 200000, NULL, 1, 1, 27, 20000, NULL, 1, 0, NULL)")
        db.query("SELECT name, amountCents, everyMonths FROM recurring_rules").use { c -> assertTrue(c.moveToFirst()); assertEquals("Stipendio", c.getString(0)); assertEquals(200000L, c.getLong(1)); assertEquals(1, c.getInt(2)) }
        db.query("SELECT COUNT(*) FROM categories").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        db.close()
    }

    @Test fun `la migrazione 7-8 rende facoltativo il conto dei movimenti e conserva movimenti e divisioni`() {
        helper.createDatabase("test-noaccount", 7).apply {
            execSQL("INSERT INTO accounts (name, type, openingCents, archived) VALUES ('Intesa', 'CONTO', 0, 0)")
            execSQL("INSERT INTO categories (name, kind, flexible, color, active, sortOrder, updatedAt, budgetUpdatedAt) VALUES ('Prelievi', 'spesa', 1, 4286611584, 1, 0, 0, 0)")
            execSQL(
                "INSERT INTO transactions (externalId, dedupKey, accountId, bookingDate, valueDate, description, amountCents, kind, categoryId, manualCategory, manualUpdatedAt, notes, origin, createdAt) " +
                    "VALUES ('e1', 'k1', 1, 19800, 19800, 'Prelievo', -80000, 'spesa', 1, 0, 0, '', 'MANUAL', 0)",
            )
            execSQL("INSERT INTO transaction_splits (txId, categoryId, amountCents, position, auto, quotaId) VALUES (1, 1, -50000, 0, 0, NULL)")
            execSQL("INSERT INTO transaction_splits (txId, categoryId, amountCents, position, auto, quotaId) VALUES (1, NULL, -30000, 1, 0, NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-noaccount", 8, true, AppDatabase.MIGRATION_7_8)
        db.query("SELECT id, accountId, amountCents, description FROM transactions").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0)); assertEquals(1L, c.getLong(1)); assertEquals(-80000L, c.getLong(2)); assertEquals("Prelievo", c.getString(3))
        }
        db.query("SELECT COUNT(*), SUM(amountCents) FROM transaction_splits").use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)); assertEquals(-80000L, c.getLong(1)) }
        // ora un movimento può non avere il conto
        db.execSQL(
            "INSERT INTO transactions (externalId, dedupKey, accountId, bookingDate, valueDate, description, amountCents, kind, manualCategory, manualUpdatedAt, notes, origin, createdAt) " +
                "VALUES ('e2', 'k2', NULL, 19801, 19801, 'Senza conto', -500, 'spesa', 0, 0, '', 'NOTIFICATION', 0)",
        )
        db.query("SELECT COUNT(*) FROM transactions WHERE accountId IS NULL").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        // un conto con movimenti non si elimina (RESTRICT): i movimenti non si perdono mai in silenzio
        db.execSQL("PRAGMA foreign_keys = ON")
        val blocked = runCatching { db.execSQL("DELETE FROM accounts WHERE id = 1") }
        assertTrue("l'eliminazione del conto con movimenti doveva essere rifiutata", blocked.isFailure)
        db.close()
    }

    @Test fun `la migrazione 8-9 aggiunge il mese contabile dei movimenti senza toccare i dati`() {
        helper.createDatabase("test-accmonth", 8).apply {
            execSQL("INSERT INTO accounts (name, type, openingCents, archived) VALUES ('Intesa', 'CONTO', 0, 0)")
            execSQL(
                "INSERT INTO transactions (externalId, dedupKey, accountId, bookingDate, valueDate, description, amountCents, kind, manualCategory, manualUpdatedAt, notes, origin, createdAt) " +
                    "VALUES ('e1', 'k1', 1, 20000, 20000, 'Stipendio', 200000, 'entrata', 0, 0, '', 'MANUAL', 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("test-accmonth", 9, true, AppDatabase.MIGRATION_8_9)
        db.query("SELECT description, amountCents, accountingMonth FROM transactions").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Stipendio", c.getString(0)); assertEquals(200000L, c.getLong(1)); assertTrue(c.isNull(2))
        }
        db.execSQL("UPDATE transactions SET accountingMonth = '2026-12' WHERE externalId = 'e1'")
        db.query("SELECT accountingMonth FROM transactions").use { c -> assertTrue(c.moveToFirst()); assertEquals("2026-12", c.getString(0)) }
        db.close()
    }
}
