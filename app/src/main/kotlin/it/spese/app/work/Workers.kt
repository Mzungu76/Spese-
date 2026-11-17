package it.spese.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import it.spese.app.MainActivity
import it.spese.app.R
import it.spese.domain.finance.AlertEngine
import it.spese.domain.finance.AlertKind
import it.spese.domain.finance.RecurringDetector
import it.spese.domain.repository.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

const val CHANNEL_ALERTS = "alerts"

/** Notifiche locali Android: nessun server, nessun dato in uscita. */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_alerts_desc) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(id: Int, title: String, text: String, bigText: String? = null) {
        if (!canPost()) return
        ensureChannel()
        val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText ?: text)).setContentIntent(pi).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
    }
}

/** Controlla ogni giorno budget, scadenze, ricorrenze e spese anomale e invia una notifica riepilogativa configurabile. */
@HiltWorker
class AlertsWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters,
    private val data: StateFlow<AppData>, private val settings: SettingsRepository, private val notifier: Notifier,
    private val recurring: RecurringRepository, private val goals: GoalRepository, private val review: it.spese.domain.finance.SubscriptionReviewRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = settings.get()
        if (!s.notificationsEnabled) return Result.success()
        val d = data.first { it.loaded }
        if (d.transactions.isEmpty() && d.dues.isEmpty()) return Result.success()
        val today = AlertEngine.referenceToday(java.time.LocalDate.now(), d.transactions)
        val alerts = AlertEngine.compute(d.transactions, d.categories, d.goal, d.dues, today, RecurringDetector.defaultExclusions(d.categories), d.calendar)
            .filter { a ->
                when (a.kind) {
                    AlertKind.BUDGET_EXCEEDED, AlertKind.BUDGET_NEAR, AlertKind.BUDGET_PACE -> s.notifyBudgets
                    AlertKind.DUE_LATE, AlertKind.DUE_SOON -> s.notifyDues
                    AlertKind.RECURRING_EXPECTED -> s.notifyRecurring
                    AlertKind.UNEXPECTED_EXPENSE -> s.notifyUnexpected
                    AlertKind.SAVINGS_BELOW_GOAL -> s.notifyBudgets
                }
            }.filter { it.level >= 1 || it.kind == AlertKind.RECURRING_EXPECTED }
        if (alerts.isNotEmpty()) {
            val critical = alerts.count { it.level >= 2 }
            val title = if (critical > 0) "$critical avvisi importanti" else "${alerts.size} avvisi da controllare"
            notifier.post(1001, title, alerts.first().message, alerts.take(6).joinToString("\n") { "• " + it.message })
        }
        it.spese.app.widget.SafeToSpendWidget.refresh(applicationContext)
        if (s.notifyDailySpend) {
            val r = runCatching { it.spese.domain.finance.SafeToSpendEngine.forApp(d, recurring.getAll(), review.observeIgnored().first(), goals = goals.getAll()) }.getOrNull()
            if (r != null && r.status != it.spese.domain.finance.SafeStatus.NOT_ENOUGH && !r.stale)
                notifier.post(1002, "Oggi: " + it.spese.domain.finance.SafeToSpendText.perDay(r), it.spese.domain.finance.SafeToSpendText.comment(r))
        }
        return Result.success()
    }
}

/** Backup automatico opzionale: salva un JSON nella memoria privata dell'app e ruota i più vecchi. */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters,
    private val backup: BackupRepository, private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = settings.get()
        if (!s.autoBackupEnabled) return Result.success()
        val due = System.currentTimeMillis() - s.lastAutoBackupAt >= TimeUnit.DAYS.toMillis(s.autoBackupEveryDays.toLong()) - TimeUnit.HOURS.toMillis(2)
        if (!due) return Result.success()
        return try {
            backup.autoBackup(s.autoBackupKeep)
            settings.update { it.copy(lastAutoBackupAt = System.currentTimeMillis()) }
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }
}

/** Pianifica/annulla i lavori periodici in base alle impostazioni (WorkManager: sopravvive al riavvio del dispositivo). */
@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    fun apply(s: AppSettings) {
        if (s.notificationsEnabled) {
            val now = LocalDateTime.now()
            var next = now.withHour(s.notifyHour).withMinute(0).withSecond(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)
            wm.enqueueUniquePeriodicWork("alerts", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AlertsWorker>(1, TimeUnit.DAYS).setInitialDelay(delay).build())
        } else wm.cancelUniqueWork("alerts")
        if (s.autoBackupEnabled) {
            wm.enqueueUniquePeriodicWork("autobackup", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        } else wm.cancelUniqueWork("autobackup")
    }
}
