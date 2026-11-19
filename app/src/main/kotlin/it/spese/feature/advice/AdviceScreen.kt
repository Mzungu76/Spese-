package it.spese.feature.advice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spese.app.common.BackHeader
import it.spese.core.common.Dates
import it.spese.core.designsystem.component.*
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.advice.Insight
import it.spese.domain.advice.InsightTarget
import it.spese.domain.advice.LearnContext
import it.spese.domain.advice.LearnTopic

/** Consigli (da regole trasparenti, ognuno con il perché) e piccola guida con esempi sui tuoi numeri. */
@Composable
fun AdviceScreen(onBack: () -> Unit, onOpen: (InsightTarget) -> Unit, vm: AdviceViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var topic by remember { mutableStateOf<LearnTopic?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Consigli e guida", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            st.review?.let { r ->
                item {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().clickable { onOpen(InsightTarget.REVIEW) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.EventAvailable, null)
                        Text("Chiusura di ${Dates.monthLabelLong(r.month)}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
            item { SectionHeader("Per te adesso", Modifier.padding(horizontal = 24.dp)) }
            if (st.insights.isEmpty()) item { Text(if (st.loaded) "Niente da segnalare: i tuoi numeri sono in ordine." else "Caricamento…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(st.insights, key = { it.id }) { i -> InsightCard(i, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), { onOpen(i.target) }, { topic = i.topic }) }
            item { SectionHeader("Imparare", Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            items(LearnTopic.entries, key = { it.name }) { t ->
                Row(Modifier.fillMaxWidth().clickable { topic = t }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    topic?.let { t -> LearnDialog(t, st.learn) { topic = null } }
}

@Composable
private fun InsightCard(i: Insight, modifier: Modifier, onOpen: () -> Unit, onLearn: () -> Unit) {
    val fin = SpeseTheme.finance
    val color = when (i.level) { 2 -> fin.negative; 1 -> fin.warning; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (i.level >= 2) Icons.Default.PriorityHigh else Icons.Default.Lightbulb, null, tint = color, modifier = Modifier.size(20.dp))
            Text(i.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(i.text, style = MaterialTheme.typography.bodyMedium)
        Text("Perché conta: " + i.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (i.target != InsightTarget.NONE) TextButton(onClick = onOpen) { Text("Apri") }
            if (i.topic != null) TextButton(onClick = onLearn) { Text("Approfondisci") }
        }
    }
}

@Composable
private fun LearnDialog(t: LearnTopic, ctx: LearnContext, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t.body, style = MaterialTheme.typography.bodyMedium)
                t.personal(ctx)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } })
}
