package it.spese.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import it.spese.app.navigation.AppRoot
import it.spese.core.designsystem.theme.SpeseTheme
import it.spese.domain.repository.AppSettings
import it.spese.domain.repository.SettingsRepository
import it.spese.domain.repository.ThemeMode
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var incoming: it.spese.app.common.IncomingFile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val s by settings.observe().collectAsState(initial = AppSettings())
            val dark = when (s.themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            SpeseTheme(darkTheme = dark) { AppRoot() }
        }
    }

    /** Uscendo dall'app il widget si riallinea ai dati appena modificati. */
    override fun onStop() { super.onStop(); it.spese.app.widget.SafeToSpendWidget.refresh(this) }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: android.content.Intent?) {
        if (i?.action == android.content.Intent.ACTION_VIEW) incoming.offer(i.data)
        i?.getStringExtra(EXTRA_ROUTE)?.let { incoming.offerRoute(it) }
    }

    companion object { const val EXTRA_ROUTE = "open_route" }
}
