package it.spese.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.spese.core.designsystem.component.Eyebrow
import it.spese.domain.health.HealthReport
import it.spese.feature.health.verdictColor

/** Lo stato di salute del bilancio a colpo d'occhio: giudizio, punteggio e la frase che dice il perché. Un tocco apre il dettaglio. */
@Composable
fun HealthCard(r: HealthReport, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = verdictColor(r.verdict)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(20.dp).testTag("health_card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(if (r.provisional) "Salute del bilancio · provvisorio" else "Salute del bilancio")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(r.verdict.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
            Text("${r.score}/100", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        LinearProgressIndicator(progress = { r.score / 100f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text(r.headline, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
        Text("Tocca per vedere indicatori e cosa fare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
