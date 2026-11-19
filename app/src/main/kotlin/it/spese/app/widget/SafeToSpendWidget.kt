package it.spese.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import it.spese.app.MainActivity
import it.spese.app.R
import it.spese.domain.finance.SafeStatus
import it.spese.domain.finance.SafeToSpendEngine
import it.spese.domain.finance.SafeToSpendReport
import it.spese.domain.finance.SafeToSpendText
import it.spese.domain.finance.SubscriptionReviewRepository
import it.spese.domain.repository.AppData
import it.spese.domain.repository.RecurringRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Widget «Disponibile oggi»: la stessa cifra e la stessa frase della Home, calcolate sul telefono. Un tocco apre l'app. */
@AndroidEntryPoint
class SafeToSpendWidget : AppWidgetProvider() {
    @Inject lateinit var data: StateFlow<AppData>
    @Inject lateinit var recurring: RecurringRepository
    @Inject lateinit var goals: it.spese.domain.repository.GoalRepository
    @Inject lateinit var review: SubscriptionReviewRepository
    @Inject lateinit var natures: it.spese.domain.health.IncomeNatureRepository
    @Inject lateinit var settings: it.spese.domain.repository.SettingsRepository
    @Inject lateinit var valuations: it.spese.domain.repository.ValuationRepository

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val d = withTimeoutOrNull(8_000) { data.first { it.loaded } }
                val report = d?.let { runCatching { SafeToSpendEngine.forApp(it, recurring.getAll(), review.observeIgnored().first(), goals = goals.getAll(), incomeOverrides = natures.get(), marginPct = settings.get().budgetMarginPct, valuations = valuations.getAll(), fromBalance = settings.get().budgetFromBalance, delayDays = settings.get().budgetDelayDays) }.getOrNull() }
                val views = render(context, report, d != null)
                appWidgetIds.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    private fun render(context: Context, r: SafeToSpendReport?, loaded: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_safe_to_spend)
        v.setTextViewText(R.id.widget_amount, if (r != null) SafeToSpendText.perDay(r) else if (loaded) "Nessun dato" else "…")
        v.setTextViewText(R.id.widget_comment, if (r != null) SafeToSpendText.comment(r) else "Importa un estratto conto per iniziare.")
        v.setTextColor(R.id.widget_amount, when (r?.status) {
            SafeStatus.OK -> 0xFF2E9E6B.toInt(); SafeStatus.BEHIND -> 0xFFE0A030.toInt(); SafeStatus.OVER -> 0xFFE5484D.toInt()
            else -> context.getColor(R.color.widget_text)
        })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.widget_root, open)
        return v
    }

    companion object {
        /** Chiede al widget (se è sulla schermata) di ricalcolarsi. Non fa nulla se non ce ne sono. */
        fun refresh(context: Context) {
            val ids = runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, SafeToSpendWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, SafeToSpendWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
    }
}
