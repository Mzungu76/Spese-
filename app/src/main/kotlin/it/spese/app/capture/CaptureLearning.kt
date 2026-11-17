package it.spese.app.capture

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modalità "Rileva app": per un paio di minuti, e solo se l'utente la avvia, ricorda (in memoria) il nome delle app che inviano notifiche,
 * così si può scegliere quella della banca senza conoscerne l'identificativo. Non legge né conserva il testo delle notifiche.
 */
@Singleton
class CaptureLearning @Inject constructor() {
    data class Seen(val packageName: String, val label: String)

    private val _seen = MutableStateFlow<List<Seen>>(emptyList())
    val seen: StateFlow<List<Seen>> = _seen.asStateFlow()
    private val _until = MutableStateFlow(0L)
    val until: StateFlow<Long> = _until.asStateFlow()

    fun start(now: Long = System.currentTimeMillis()) { _seen.value = emptyList(); _until.value = now + WINDOW_MS }
    fun stop() { _until.value = 0L }
    val active: Boolean get() = System.currentTimeMillis() < _until.value

    fun note(packageName: String, label: String) {
        if (!active || packageName in IGNORED) return
        if (_seen.value.none { it.packageName == packageName }) _seen.value = _seen.value + Seen(packageName, label)
    }

    companion object {
        const val WINDOW_MS = 120_000L
        private val IGNORED = setOf("android", "com.android.systemui", "com.android.providers.downloads", "com.google.android.gms", "com.google.android.packageinstaller")
    }
}
