package it.spese.feature.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import it.spese.app.common.DataMenu
import it.spese.app.common.LargeHeader
import it.spese.core.designsystem.component.SectionHeader

/** Tutto il resto, raggruppato: analisi, gestione dei dati e impostazioni. Le sezioni di tutti i giorni stanno nella barra in basso. */
class MoreActions(
    val onCategories: () -> Unit, val onHealth: () -> Unit, val onBudgets: () -> Unit, val onPlan: () -> Unit, val onTrends: () -> Unit, val onAdvice: () -> Unit, val onReview: () -> Unit, val onAlerts: () -> Unit,
    val onRecurring: () -> Unit, val onSubscriptions: () -> Unit, val onReports: () -> Unit, val onAccounts: () -> Unit, val onShare: () -> Unit, val onTransfers: () -> Unit,
    val onImport: () -> Unit, val onScan: () -> Unit, val onSettings: () -> Unit, val onReminders: () -> Unit, val onCustomize: () -> Unit,
)

@Composable
fun MoreScreen(menu: DataMenu, a: MoreActions, bottomPadding: PaddingValues = PaddingValues()) {
    Column(Modifier.fillMaxSize()) {
        LargeHeader("Altro", menu = menu)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 24.dp)) {
            item { SectionHeader("Capire i soldi", Modifier.padding(horizontal = 24.dp)) }
            item { MoreRow("customize", Icons.Default.Dashboard, "Personalizza le pagine", "Widget di Home, Movimenti, Obiettivi e Patrimonio, o un setup pronto", a.onCustomize) }
            item { MoreRow("health", Icons.Default.MonitorHeart, "Salute del bilancio", "Giudizio, punteggio e cosa fare, dal tuo storico", a.onHealth) }
            item { MoreRow("categories", Icons.Default.Category, "Categorie", "Dove vanno i soldi, budget e confronti", a.onCategories) }
            item { MoreRow("budgets", Icons.Default.Tune, "Budget per categoria", "Imposta o correggi i budget uno per uno", a.onBudgets) }
            item { MoreRow("plan", Icons.Default.Flag, "Piano e simulazioni", "«E se…», tagli per categoria, scenari", a.onPlan) }
            item { MoreRow("trends", Icons.Default.Timeline, "Tendenze e proiezioni", "Ogni categoria contro lo storico", a.onTrends) }
            item { MoreRow("advice", Icons.Default.Lightbulb, "Consigli e guida", "Cosa conviene fare e perché", a.onAdvice) }
            item { MoreRow("review", Icons.Default.EventAvailable, "Chiusura del mese", "Risparmio, 50/30/20 e azioni da applicare", a.onReview) }
            item { MoreRow("reminders", Icons.Default.Alarm, "Sveglie", "Promemoria per importare gli estratti conto e altro", a.onReminders) }
            item { MoreRow("alerts", Icons.Default.Notifications, "Avvisi", "Budget, scadenze e spese non previste", a.onAlerts) }
            item { SectionHeader("Movimenti automatici", Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            item { MoreRow("recurring", Icons.Default.Autorenew, "Ricorrenti", "Stipendio, affitto incassato, abbonamenti", a.onRecurring) }
            item { MoreRow("subscriptions", Icons.Default.Subscriptions, "Abbonamenti e rate", "Riconosciuti dai movimenti", a.onSubscriptions) }
            item { SectionHeader("Dati", Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            item { MoreRow("transfers", Icons.Default.SwapHoriz, "Giroconti probabili", "Spostamenti tra i tuoi conti da confermare", a.onTransfers) }
            item { MoreRow("import", Icons.Default.UploadFile, "Importa estratto conto", "Excel, CSV o TXT dalla banca", a.onImport) }
            item { MoreRow("scan", Icons.Default.DocumentScanner, "Scansiona uno scontrino", "Importo, data ed esercente", a.onScan) }
            item { MoreRow("reports", Icons.Default.PictureAsPdf, "Report PDF", "Riepiloghi pronti o personalizzati", a.onReports) }
            item { MoreRow("accounts", Icons.Default.AccountBalanceWallet, "Conti", "Nomi, tipi e saldi iniziali", a.onAccounts) }
            item { MoreRow("share", Icons.Default.Share, "Condivisione", "Budget e ritmo di un conto per un'altra persona", a.onShare) }
            item { MoreRow("settings", Icons.Default.Settings, "Impostazioni e backup", "Notifiche, mese contabile, backup", a.onSettings) }
        }
    }
}

@Composable
private fun MoreRow(key: String, icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("more_$key").padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
