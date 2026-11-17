package it.spese.app.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.core.model.TxKind
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
    val onSettings: () -> Unit, val undoAvailable: Boolean, val onUndo: () -> Unit, val onReports: () -> Unit = {}, val onRecurring: () -> Unit = {},
)

@Composable
fun OverflowMenu(menu: DataMenu) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Menu dati") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Analisi e grafici") }, { open = false; menu.onAnalytics() }, leadingIcon = { Icon(Icons.Default.Insights, null) })
            DropdownMenuItem({ Text("Entrate e spese ricorrenti") }, { open = false; menu.onRecurring() }, leadingIcon = { Icon(Icons.Default.Autorenew, null) })
            DropdownMenuItem({ Text("Report PDF") }, { open = false; menu.onReports() }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) })
            DropdownMenuItem({ Text("Conti") }, { open = false; menu.onAccounts() }, leadingIcon = { Icon(Icons.Default.AccountBalance, null) })
            if (menu.undoAvailable) DropdownMenuItem({ Text("Annulla ultima importazione") }, { open = false; menu.onUndo() }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) })
            HorizontalDivider()
            DropdownMenuItem({ Text("Backup e impostazioni") }, { open = false; menu.onSettings() }, leadingIcon = { Icon(Icons.Default.Settings, null) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(title: String, menu: DataMenu, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = { actions(); OverflowMenu(menu) })
}

/** Titolo grande a sinistra e azioni tonde a destra: l'intestazione delle schermate principali. */
@Composable
fun LargeHeader(title: String, modifier: Modifier = Modifier, menu: DataMenu? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 8.dp, top = 8.dp).heightIn(min = 56.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        actions()
        if (menu != null) OverflowMenu(menu)
    }
}

/** Intestazione di una schermata secondaria: freccia indietro, titolo grande e azioni. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", onBack)
        Text(title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        actions()
    }
}

/** Pulsante tondo grigio scuro con icona (cerca, filtri, campanella…). */
@Composable
fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Boolean = false, primary: Boolean = false) {
    Box(modifier.padding(horizontal = 2.dp)) {
        FilledIconButton(onClick = onClick, Modifier.size(40.dp), colors = if (primary) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        else IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface)) {
            Icon(icon, description, Modifier.size(20.dp))
        }
        if (badge) Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).size(9.dp).background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } }, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@HiltViewModel
class RootViewModel @Inject constructor(settings: SettingsRepository, private val undo: UndoImportUseCase, private val generateRecurring: it.spese.domain.usecase.GenerateRecurringUseCase, val messenger: UiMessenger, private val filters: FilterStore, val incoming: IncomingFile) : ViewModel() {
    /** Crea i movimenti dovuti dalle entrate e spese ricorrenti (all'avvio e a ogni ritorno nell'app). */
    fun generateRecurring() { viewModelScope.launch { try { val n = generateRecurring.invoke(); if (n > 0) messenger.show(if (n == 1) "Creato 1 movimento ricorrente: controllalo in Verifica" else "Creati $n movimenti ricorrenti: controllali in Verifica") } catch (_: Exception) { } } }
    fun requestSearch() = filters.requestSearch()
    fun showTransfers() = filters.update { it.copy(kind = TxKind.TRASFERIMENTO) }
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


/** Barra di ricerca a pillola con il pulsante dei filtri accanto (come nelle app bancarie): libera l'intestazione dai pulsanti. */
@Composable
fun SearchFilterBar(onSearch: () -> Unit, onFilters: () -> Unit, filtersActive: Boolean, modifier: Modifier = Modifier, hint: String = "Cerca movimenti") {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).height(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onSearch).padding(horizontal = 14.dp)
            .semantics { contentDescription = hint }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RoundIconButton(Icons.Default.Tune, "Filtri", onFilters, badge = filtersActive)
    }
}
