package it.spese.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import it.spese.data.backup.DatabaseSeeder
import it.spese.domain.backup.LegacyBackupCodec
import it.spese.domain.repository.AppData
import it.spese.domain.repository.RestoreMode
import it.spese.domain.usecase.RestoreBackupUseCase
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Test UI end-to-end sull'applicazione reale (Hilt + Room + Navigation + Compose) eseguiti su JVM con Robolectric:
 * si avvia MainActivity, si naviga tra le cinque sezioni e si verificano le operazioni principali.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MainFlowTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var seeder: DatabaseSeeder
    @Inject lateinit var restore: RestoreBackupUseCase
    @Inject lateinit var data: StateFlow<AppData>
    @Inject lateinit var txRepo: it.spese.domain.repository.TransactionRepository

    @Before fun setUp() { hilt.inject(); runBlocking { seeder.ensureSeeded() } }

    private fun waitText(text: String, substring: Boolean = false, ms: Long = 5000) {
        try { compose.waitUntil(ms) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Testo \"$text\" non trovato. Schermata:\n" + compose.onRoot(useUnmergedTree = true).printToString(60), e) }
    }

    private fun AndroidComposeTestRule<*, *>.tab(label: String) { onNode(hasText(label) and hasClickAnyAncestor()).performClick(); waitForIdle() }
    private fun hasClickAnyAncestor() = hasAnyAncestor(isRoot()).and(hasClickAction().or(hasAnyAncestor(hasClickAction())))

    @Test fun avvio_mostra_la_panoramica_vuota_con_le_azioni_di_importazione() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Nessun movimento").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Importa estratto conto").assertIsDisplayed()
        compose.onNodeWithText("Scansiona scontrino").assertIsDisplayed()
        compose.onNodeWithText("Ho già un backup: importa").assertIsDisplayed()
    }

    @Test fun le_cinque_sezioni_principali_sono_raggiungibili() {
        compose.waitForIdle()
        listOf("Movimenti", "Categorie", "Piano", "Avvisi", "Panoramica").forEach { label ->
            compose.onAllNodesWithText(label).filterToOne(hasAnyAncestor(hasText("Panoramica").not().or(isRoot())).and(hasClickAction().or(hasAnyAncestor(hasClickAction())))).performClick()
            compose.waitForIdle()
        }
        // la sezione Categorie mostra le categorie predefinite
        compose.onAllNodesWithText("Categorie").onFirst().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Supermercato e alimentari").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Supermercato e alimentari").assertExists()
    }

    @Test fun aggiunta_di_un_movimento_manuale_classificato_dalle_regole() {
        compose.onAllNodesWithText("Movimenti").filterToOne(hasClickAction().or(hasAnyAncestor(hasClickAction()))).performClick()
        waitText("Nessun movimento")
        compose.onNodeWithContentDescription("Aggiungi movimento").performClick()
        waitText("Nuovo movimento"); waitText("Importo")
        compose.onNodeWithText("Importo").performTextInput("23,45")
        compose.onNodeWithText("Descrizione").performTextInput("Pagamento POS COOP ALLEANZA")
        compose.onNodeWithText("Salva").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        try { compose.waitUntil(8000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20); data.value.transactions.size == 1 } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Movimento non salvato. conti=${data.value.accounts.size} dbCount=${runBlocking { txRepo.getAll().size }} stateCount=${data.value.transactions.size}", e) }
        val t = data.value.transactions.single()
        assertEquals("-23.45", t.amount.toPlainString())
        assertEquals("Supermercato e alimentari", data.value.categoryName(t.categoryId))
        compose.waitUntil(5000) { compose.onAllNodesWithText("COOP ALLEANZA", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun backup_della_versione_html_ripristinato_e_mostrato_nella_panoramica() {
        val env = LegacyBackupCodec.parse(String(javaClass.getResourceAsStream("/backupA.json")!!.readBytes()))
        runBlocking { restore(env, RestoreMode.Replace) }
        compose.waitUntil(10000) { data.value.transactions.size == 223 }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Saldo (entrate − spese)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saldo (entrate − spese)").assertExists()
        compose.onNodeWithText("Spese per categoria").assertExists()
        // avvisi di budget calcolati sui dati importati (come nell'HTML)
        compose.waitUntil(5000) { compose.onAllNodesWithText("superato", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(data.value.categories.any { it.budgetMonthly != null })
    }
}
