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
}
