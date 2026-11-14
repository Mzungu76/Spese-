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
    @Inject lateinit var filters: it.spese.app.common.FilterStore

    @Before fun setUp() { hilt.inject(); runBlocking { seeder.ensureSeeded() } }

    private fun waitText(text: String, substring: Boolean = false, ms: Long = 8000) {
        try { compose.waitUntil(ms) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Testo \"$text\" non trovato. Schermata:\n" + compose.onAllNodes(isRoot(), useUnmergedTree = true).onLast().printToString(60), e) }
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
        listOf("transactions", "categories", "plan", "dashboard").forEach { route ->
            compose.onNodeWithTag("tab_$route").performClick(); compose.waitForIdle()
        }
        compose.onNodeWithTag("tab_categories").performClick(); compose.waitForIdle()
        // la sezione Categorie mostra le categorie predefinite
        compose.waitUntil(5000) { compose.onAllNodesWithText("Supermercato e alimentari").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Supermercato e alimentari").assertExists()
    }

    @Test fun aggiunta_di_un_movimento_manuale_classificato_dalle_regole() {
        compose.onAllNodesWithText("Movimenti").filterToOne(hasClickAction().or(hasAnyAncestor(hasClickAction()))).performClick()
        waitText("Nessun movimento")
        compose.onNodeWithTag("fab_add").performClick()
        compose.onNodeWithTag("add_manual").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
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
        waitText("Hai ", substring = true, ms = 10000)   // "Hai messo da parte" oppure "Hai speso in più…"
        waitText("Entrate"); waitText("Spese")
        // avvisi calcolati sui dati importati (budget superati, scadenze…)
        waitText("da guardare", substring = true)
        assertTrue(data.value.categories.any { it.budgetMonthly != null })
    }

    private fun goTab(label: String) {
        val route = mapOf("Movimenti" to "transactions", "Categorie" to "categories", "Piano" to "plan", "Panoramica" to "dashboard").getValue(label)
        compose.onNodeWithTag("tab_$route").performClick()
        compose.waitForIdle()
    }

    private fun openMenu(item: String) {
        compose.onAllNodesWithContentDescription("Menu dati").onFirst().performClick()
        compose.onNodeWithText(item).performClick()
        compose.waitForIdle()
    }

    @Test fun tutte_le_schermate_si_compongono_con_dati_reali() {
        val env = LegacyBackupCodec.parse(String(javaClass.getResourceAsStream("/backupA.json")!!.readBytes()))
        runBlocking { restore(env, RestoreMode.Replace) }
        compose.waitUntil(10000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); data.value.transactions.size == 223 }

        // di partenza l'app mostra il mese corrente: per contare tutti i movimenti si sceglie "Tutto"
        filters.update { f -> f.copy(period = it.spese.domain.finance.Period.All) }
        goTab("Movimenti"); waitText("223 movimenti")
        // ricerca testuale: il campo non perde lettere e la lista si restringe
        compose.onNodeWithContentDescription("Cerca").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("search_field").performTextInput("coop")
        compose.waitUntil(8000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); compose.onAllNodesWithText("223 movimenti").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("search_field").assert(hasText("coop"))
        compose.onNodeWithContentDescription("Cancella ricerca").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); waitText("223 movimenti")
        // sezione Verifica dei movimenti incerti
        compose.onNodeWithTag("review_toggle").assertExists()
        compose.onNodeWithText("Verifica", substring = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitUntil(8000) { compose.onAllNodesWithText("Tutto a posto").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("da controllare", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tutti").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); waitText("223 movimenti")
        goTab("Categorie"); waitText("Supermercato e alimentari")
        // tendenze per categoria con proiezioni
        compose.onNode(hasText("Tendenze") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitText("Bilancio mensile atteso"); waitText("Effetto cumulato")
        compose.onNodeWithContentDescription("Indietro").performClick(); waitText("Supermercato e alimentari")
        // dettaglio di una categoria: esercenti e andamento
        compose.onNodeWithText("Supermercato e alimentari").performClick(); waitText("Budget mensile")
        // simulazione "e se la riducessi?": il taglio si salva nello scenario di base del Piano
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("E se la riducessi?"))
        compose.onNode(hasText("Salva nel Piano") and hasClickAction()).assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        val superId = data.value.categories.first { it.name == "Supermercato e alimentari" }.id
        compose.waitUntil(8000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); data.value.cuts[superId] == 20 }
        compose.onNodeWithContentDescription("Indietro").performClick(); waitText("Regole · 32")
        // Gestione: regole e "Da fare"
        compose.onNodeWithText("Regole · 32").performClick(); waitText("Vince la prima regola", substring = true)
        compose.onNodeWithText("Da fare", substring = true).performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Indietro").performClick(); compose.waitForIdle()
        goTab("Piano"); waitText("Risultato della simulazione"); waitText("Obiettivo di risparmio", substring = true)
        goTab("Panoramica")
        // confronto di categorie in Home: grafico e incidenza sulle entrate
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Confronta"))
        compose.onNode(hasText("Confronta") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitText("Fine")
        val rankId = data.value.categories.first { it.name == "Supermercato e alimentari" }.id
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("rank-$rankId"))
        compose.onNodeWithTag("rank-$rankId").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        repeat(30) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(100) }   // il confronto si calcola fuori dal thread principale
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Incidenza sulle entrate"))
        waitText("Incidenza sulle entrate", substring = true)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Azzera")); compose.onNode(hasText("Azzera") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNode(hasContentDescription("Avvisi", substring = true)).performClick()
        waitText("Budget del mese in corso"); waitText("superato", substring = true)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()

        // analisi: tutti i tipi di grafico
        goTab("Panoramica")
        openMenu("Analisi e grafici"); waitText("Ciambella")
        listOf("Ciambella", "Barre", "Impilate", "Linee", "Cumulato", "Periodi", "Categorie", "Tendenze", "Distribuzione", "Previsioni").forEach { t ->
            compose.onAllNodesWithText(t).onFirst().performScrollTo().performClick(); compose.waitForIdle()
        }
        waitText("Previsione a 6 mesi", substring = true)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        openMenu("Backup e impostazioni"); waitText("Backup e ripristino")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Sincronizzazione con un file condiviso")); waitText("Sincronizzazione con un file condiviso")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Movimenti automatici"))
        // Movimenti automatici: schermata di impostazione e prova del riconoscimento
        compose.onNode(hasText("Imposta") and hasClickAction()).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitText("Accesso alle notifiche"); waitText("Interruttore grigio", substring = true); waitText("Revolut"); waitText("Google Wallet")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Prova un testo"))
        compose.onNodeWithText("Testo").performTextInput("Hai pagato 12,50 € a COOP SPA con carta")
        waitText("Riconosciuta", substring = true)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        openMenu("Conti"); waitText("Intesa"); waitText("Revolut")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
        // Report PDF: modelli, personalizzazione e creazione
        openMenu("Report PDF"); waitText("Modelli pronti")
        compose.onNodeWithTag("preset_monthly").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitText("Titolo e nota")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("report_generate"))
        compose.onNodeWithTag("report_generate").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitUntil(20000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); compose.onAllNodesWithTag("report_save").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Il tuo report", substring = true).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Nessun movimento nel periodo").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Impossibile creare il report", substring = true).fetchSemanticsNodes().isNotEmpty() }   // sulla JVM di prova PdfDocument non c'è: basta che la richiesta parta e risponda
    }

    @Test fun il_pulsante_piu_apre_le_quattro_azioni() {
        compose.waitForIdle()
        compose.onNodeWithTag("fab_add").performClick()
        listOf("add_manual", "add_scan", "add_import", "add_restore").forEach { compose.onNodeWithTag(it).assertExists() }
        waitText("Cosa vuoi aggiungere?")
    }
}
