package it.spese.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import it.spese.core.database.AppDatabase
import it.spese.data.backup.BackupRepositoryImpl
import it.spese.data.backup.DatabaseSeeder
import it.spese.data.files.DocumentFiles
import it.spese.data.repository.*
import it.spese.data.settings.SettingsRepositoryImpl
import it.spese.domain.usecase.*

/** Ambiente di test: database Room reale in memoria (Robolectric) e repository/casi d'uso veri. */
class TestEnv {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val tx = TransactionRepositoryImpl(db)
    val cats = CategoryRepositoryImpl(db)
    val rules = RuleRepositoryImpl(db)
    val accounts = AccountRepositoryImpl(db)
    val dues = DueRepositoryImpl(db)
    val scenarios = ScenarioRepositoryImpl(db)
    val settings = SettingsRepositoryImpl(context)
    val files = DocumentFiles(context)
    val seeder = DatabaseSeeder(db)
    val backup = BackupRepositoryImpl(context, db, files, seeder, settings)
    val reclassify = ReclassifyAllUseCase(tx, cats, rules)
    val restore = RestoreBackupUseCase(backup, reclassify)
    val commit = CommitImportUseCase(tx, accounts, cats, rules, settings)
    val undo = UndoImportUseCase(tx, backup, settings)
    val capture = CaptureNotificationUseCase(tx, accounts, cats, rules, settings)
    fun resource(name: String): ByteArray = TestEnv::class.java.getResourceAsStream("/$name")!!.readBytes()
    fun close() = db.close()
}
