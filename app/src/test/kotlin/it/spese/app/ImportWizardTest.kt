package it.spese.app

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import it.spese.app.common.UiMessenger
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.data.backup.DatabaseSeeder
import it.spese.domain.importing.ColumnRole
import it.spese.domain.repository.AppData
import it.spese.domain.usecase.*
import it.spese.feature.importer.ImportScreen
import it.spese.feature.importer.ImportStep
import it.spese.feature.importer.ImportViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Wizard di importazione guidata: riconoscimento colonne dal contenuto, correzione, controllo e formato ricordato. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ImportWizardTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var seeder: DatabaseSeeder
    @Inject lateinit var data: StateFlow<AppData>
    @Inject lateinit var read: ReadStatementUseCase
    @Inject lateinit var preview: PreviewImportUseCase
    @Inject lateinit var commit: CommitImportUseCase
    @Inject lateinit var resolvePending: it.spese.domain.usecase.ResolvePendingAmountsUseCase
    @Inject lateinit var profiles: ImportProfilesUseCase
    @Inject lateinit var messenger: UiMessenger
    @Inject lateinit var profileRepo: it.spese.domain.repository.ImportProfileRepository

    @Before fun setUp() { hilt.inject(); runBlocking { seeder.ensureSeeded(); profileRepo.all().forEach { profileRepo.delete(it.signature) } } }

    private fun vm() = ImportViewModel(ApplicationProvider.getApplicationContext<Application>(), data, read, preview, commit, resolvePending, profiles, messenger)
    private fun file(name: String, text: String): Uri = Uri.fromFile(File(ApplicationProvider.getApplicationContext<Application>().cacheDir, name).also { it.writeText(text) })
    private fun idle(cond: () -> Boolean) = compose.waitUntil(10000) { ShadowLooper.idleMainLooper(); Thread.sleep(20); cond() }
    private fun waitText(t: String, sub: Boolean = false) = compose.waitUntil(10000) { ShadowLooper.idleMainLooper(); compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty() }

    // banca sconosciuta: titoli in inglese, importo senza segno + colonna D/A, dati con punto e virgola
    private val bankA = "Operation date;Details;Value;Dr/Cr\n05/03/2024;POS COOP ALLEANZA;32,70;D\n06/03/2024;BONIFICO STIPENDIO;1.850,00;A\n07/03/2024;POS BAR CENTRALE;1,30;D\n08/03/2024;PRELIEVO ATM;50,00;D\n"

    @Test fun riconosce_colonne_sconosciute_dal_contenuto_e_importa() {
        val vm = vm()
        vm.pick(listOf(file("bankA.csv", bankA)))
        idle { vm.state.value.step == ImportStep.COLUMNS }
        compose.setContent { SpeseTheme(darkTheme = true) { ImportScreen(onBack = {}, vm = vm) } }

        // l'analisi ha capito data, descrizione, importo e segno senza conoscere la banca
        assertEquals(listOf(ColumnRole.DATE, ColumnRole.DESCRIPTION, ColumnRole.AMOUNT, ColumnRole.SIGN), vm.state.value.roles)
        waitText("Formato riconosciuto")
        compose.onAllNodesWithText("Data", substring = true).onFirst().assertExists()
        assertEquals(4, vm.state.value.preview!!.rows.size)
        assertEquals(listOf("-32.70", "1850.00", "-1.30", "-50.00"), vm.state.value.preview!!.rows.map { it.row.amount.toPlainString() })

        // l'utente può cambiare il ruolo di una colonna: l'anteprima si aggiorna
        vm.setRole(3, ColumnRole.IGNORE)
        idle { vm.state.value.preview?.rows?.all { it.row.amount.signum() > 0 } == true }
        vm.setRole(3, ColumnRole.SIGN)
        idle { vm.state.value.preview?.rows?.any { it.row.amount.signum() < 0 } == true }

        compose.onNodeWithText("Avanti: controlla i movimenti").performScrollTo().performClick()
        waitText("4 movimenti nuovi")
        vm.setAccount("Conto Test"); vm.setRemember(false)
        vm.confirm()
        idle { vm.state.value.step == ImportStep.DONE }
        assertEquals(4, data.value.transactions.size)
        assertEquals(setOf("-32.70", "1850.00", "-1.30", "-50.00"), data.value.transactions.map { it.amount.toPlainString() }.toSet())
    }

    @Test fun il_formato_confermato_viene_ricordato_e_riapplicato() {
        val first = vm()
        first.pick(listOf(file("a.csv", bankA)))
        idle { first.state.value.step == ImportStep.COLUMNS }
        first.setRememberName("Banca A")
        first.setAccount("Conto A"); first.confirm()
        idle { first.state.value.step == ImportStep.DONE }

        // secondo estratto della stessa banca (altre date): formato già noto
        val second = vm()
        second.pick(listOf(file("b.csv", "Operation date;Details;Value;Dr/Cr\n10/04/2024;POS ENI;40,00;D\n11/04/2024;POS CONAD;22,10;D\n12/04/2024;STIPENDIO;1.900,00;A\n13/04/2024;POS BAR;2,00;D\n")))
        idle { second.state.value.step == ImportStep.COLUMNS }
        assertEquals("Banca A", second.state.value.profileName)
        compose.setContent { SpeseTheme(darkTheme = true) { ImportScreen(onBack = {}, vm = second) } }
        waitText("Formato ricordato: Banca A")
    }

    @Test fun file_non_riconosciuto_guida_alla_scelta_manuale() {
        val vm = vm()
        vm.pick(listOf(file("x.csv", "Nome;Citta\nMario;Roma\nLuca;Milano\n")))
        idle { vm.state.value.step == ImportStep.COLUMNS }
        compose.setContent { SpeseTheme(darkTheme = true) { ImportScreen(onBack = {}, vm = vm) } }
        waitText("Non ho riconosciuto tutto", sub = true)
        compose.onNodeWithText("Avanti: controlla i movimenti").assertIsNotEnabled()
        // scegliendo a mano i ruoli il file diventa importabile? (qui non ci sono date/importi: resta bloccato)
        assertFalse(vm.state.value.mapping.isUsable)
    }

    @Test fun xls_binario_viene_spiegato() {
        val vm = vm()
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        val f = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "vecchio.xls").also { it.writeBytes(ole) }
        vm.pick(listOf(Uri.fromFile(f)))
        idle { vm.state.value.step == ImportStep.DONE }
        assertTrue(vm.state.value.log.single().contains("xlsx"))
    }
}
