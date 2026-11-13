package it.spese.app.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.domain.repository.SettingsRepository
import it.spese.domain.usecase.UndoImportUseCase
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Voci del menu "Dati" disponibili in ogni schermata principale. */
data class DataMenu(
    val onImport: () -> Unit, val onScan: () -> Unit, val onAnalytics: () -> Unit, val onAccounts: () -> Unit,
    val onSettings: () -> Unit, val undoAvailable: Boolean, val onUndo: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(title: String, menu: DataMenu, actions: @Composable RowScope.() -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        actions = {
            actions()
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Menu dati") }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text("Importa estratto conto") }, { open = false; menu.onImport() }, leadingIcon = { Icon(Icons.Default.UploadFile, null) })
                    DropdownMenuItem({ Text("Scansiona documento") }, { open = false; menu.onScan() }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null) })
                    DropdownMenuItem({ Text("Analisi e grafici") }, { open = false; menu.onAnalytics() }, leadingIcon = { Icon(Icons.Default.Insights, null) })
                    DropdownMenuItem({ Text("Conti") }, { open = false; menu.onAccounts() }, leadingIcon = { Icon(Icons.Default.AccountBalance, null) })
                    if (menu.undoAvailable) DropdownMenuItem({ Text("Annulla ultima importazione") }, { open = false; menu.onUndo() }, leadingIcon = { Icon(Icons.Default.Undo, null) })
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Backup e impostazioni") }, { open = false; menu.onSettings() }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } }, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@HiltViewModel
class RootViewModel @Inject constructor(settings: SettingsRepository, private val undo: UndoImportUseCase, val messenger: UiMessenger, private val filters: FilterStore, val incoming: IncomingFile) : ViewModel() {
    fun showCategory(id: Long?) = filters.update { it.copy(categoryId = id, period = it.period) }
    val undoAvailable = settings.observe().map { it.lastUndo.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val onboardingDone = settings.observe().map { it.onboardingDone }
    fun undoLast() = viewModelScope.launch {
        val ok = try { undo() } catch (e: Exception) { false }
        messenger.show(if (ok) "Importazione annullata" else "Niente da annullare")
    }
}

/** Miniatura di un'immagine salvata (decodifica ridotta, fuori dal thread principale). */
@Composable
fun FileImage(path: String, modifier: Modifier = Modifier, maxPx: Int = 800, contentScale: ContentScale = ContentScale.Crop) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > maxPx * 2) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }
    bmp?.let { Image(it.asImageBitmap(), "Documento", modifier, contentScale = contentScale) } ?: Box(modifier) { CircularProgressIndicator(Modifier.size(24.dp)) }
}
