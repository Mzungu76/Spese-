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
import it.spese.core.designsystem.component.MoneyText
import it.spese.domain.networth.NetWorthReport

/** Il saldo di ogni conto e il totale, a colpo d'occhio. Un tocco apre il Patrimonio. */
@Composable
fun BalancesCard(r: NetWorthReport, managed: Set<Long>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(20.dp).testTag("balances_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Saldi dei conti")
        MoneyText(r.total, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, colored = false, decimals = false)
        r.accounts.forEach { b ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.account.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    val note = listOfNotNull(b.account.type.label, if (b.account.id in managed) "gestito da altri" else null).joinToString(" · ")
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                MoneyText(b.balance, style = MaterialTheme.typography.titleMedium, colored = false, decimals = false)
            }
        }
    }
}
