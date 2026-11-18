package it.spese.domain.widgets

import kotlinx.coroutines.flow.Flow

/** Le pagine che si possono comporre con i widget. */
enum class WidgetPage(val label: String) { HOME("Home") }

/** Come i widget sono raggruppati nella galleria. */
enum class WidgetGroup(val label: String) { TODAY("Oggi"), MONTH("Spese ed entrate"), TODO("Da sistemare"), LAYOUT("Struttura") }

/** Un widget che l'app sa mostrare: un blocco con un solo compito. `repeatable` = se ne possono mettere più copie (es. i titoli di sezione). */
data class WidgetSpec(
    val id: String, val title: String, val description: String, val group: WidgetGroup, val page: WidgetPage, val repeatable: Boolean = false,
)

/** Un widget messo in una pagina. `uid` è unico nella pagina; `options` sono le impostazioni (es. il testo di un titolo). */
data class WidgetInstance(val uid: String, val widgetId: String, val enabled: Boolean = true, val options: Map<String, String> = emptyMap())

interface WidgetLayoutRepository {
    /** Le composizioni salvate; una pagina senza voce usa quella predefinita. */
    fun observe(): Flow<Map<WidgetPage, List<WidgetInstance>>>
    suspend fun get(): Map<WidgetPage, List<WidgetInstance>>
    suspend fun set(page: WidgetPage, items: List<WidgetInstance>)
    /** Toglie la composizione salvata: la pagina torna a quella predefinita. */
    suspend fun reset(page: WidgetPage)
    suspend fun setAll(map: Map<WidgetPage, List<WidgetInstance>>)
}

object WidgetCatalog {
    const val SECTION = "section"
    private fun h(id: String, title: String, desc: String, g: WidgetGroup, repeatable: Boolean = false) = WidgetSpec(id, title, desc, g, WidgetPage.HOME, repeatable)

    val specs: List<WidgetSpec> = listOf(
        h(SECTION, "Titolo di sezione", "Una riga di titolo per raggruppare i widget", WidgetGroup.LAYOUT, repeatable = true),
        h("safe", "Disponibile oggi", "Quanto puoi ancora spendere nel mese, al giorno", WidgetGroup.TODAY),
        h("health", "Salute del bilancio", "Giudizio e punteggio sul tuo storico", WidgetGroup.TODAY),
        h("advice", "Consigli e chiusura del mese", "Il consiglio più importante e il mese da chiudere", WidgetGroup.TODAY),
        h("balances", "Saldi dei conti", "Il saldo di ogni conto e il totale", WidgetGroup.TODAY),
        h("hero", "Risparmio del periodo", "Quanto hai messo da parte (o speso in più) e in che percentuale", WidgetGroup.MONTH),
        h("cashflow", "Entrate e spese", "Grafico del periodo, con il confronto tra categorie", WidgetGroup.MONTH),
        h("period", "Periodo", "Il selettore: settimana, mese, 3 mesi, anno, tutto", WidgetGroup.MONTH),
        h("pace", "Ritmo di spesa", "Stai spendendo più o meno del solito, giorno per giorno", WidgetGroup.MONTH),
        h("watch", "Categorie in aumento", "Dove stai spendendo più dello storico", WidgetGroup.MONTH),
        h("opps", "Dove puoi risparmiare", "Le categorie su cui conviene intervenire", WidgetGroup.MONTH),
        h("categories", "Dove vanno i soldi", "Le categorie con importi e variazioni (spese o entrate)", WidgetGroup.MONTH),
        h("alerts", "Avvisi", "Budget, scadenze e spese non previste", WidgetGroup.TODO),
        h("unassigned", "Movimenti senza conto", "Quelli da assegnare a un conto", WidgetGroup.TODO),
        h("transfer_pairs", "Giroconti probabili", "Spostamenti tra i tuoi conti da confermare", WidgetGroup.TODO),
        h("pending_amounts", "Importi da completare", "Movimenti segnalati da notifiche senza cifra", WidgetGroup.TODO),
        h("notif_review", "Notifiche da controllare", "Quelle da ignorare o da registrare", WidgetGroup.TODO),
        h("subs", "Abbonamenti e rate", "Costo mensile e novità", WidgetGroup.TODO),
    )

    private val byId = specs.associateBy { it.id }
    fun spec(id: String): WidgetSpec? = byId[id]
    fun forPage(page: WidgetPage): List<WidgetSpec> = specs.filter { it.page == page }
}

/** Un widget in una composizione predefinita. */
data class PresetItem(val widgetId: String, val options: Map<String, String> = emptyMap())

/** Una composizione pronta (un «setup»): per pagina, l'elenco ordinato dei widget. */
data class LayoutPreset(val id: String, val title: String, val description: String, val layouts: Map<WidgetPage, List<PresetItem>>)

object LayoutPresets {
    private fun sec(t: String) = PresetItem(WidgetCatalog.SECTION, mapOf("title" to t))
    private fun w(id: String, vararg o: Pair<String, String>) = PresetItem(id, o.toMap())

    /** Quella che l'app mostrava prima dei widget: resta la composizione di partenza. */
    val BALANCED = LayoutPreset("balanced", "Equilibrato", "Tutto, nell'ordine di sempre: oggi, il mese, cosa sistemare", mapOf(WidgetPage.HOME to listOf(
        sec("Oggi"), w("health"), w("safe"), w("advice"),
        sec("Questo mese"), w("hero"), w("cashflow"), w("period"), w("balances"), w("pace"), w("watch"), w("opps"), w("categories"),
        sec("Da sistemare"), w("alerts"), w("unassigned"), w("transfer_pairs"), w("pending_amounts"), w("notif_review"), w("subs"),
    )))
    val SPENDING = LayoutPreset("spending", "Focus spese", "Quanto puoi spendere, il ritmo e dove vanno i soldi", mapOf(WidgetPage.HOME to listOf(
        sec("Oggi"), w("safe"), w("pace"), w("period"),
        sec("Dove vanno i soldi"), w("categories", "kind" to "SPESA"), w("watch"), w("opps"), w("cashflow"),
        sec("Da sistemare"), w("alerts"), w("subs"), w("unassigned"), w("transfer_pairs"),
    )))
    val INCOME = LayoutPreset("income", "Focus entrate", "Quanto incassi, da dove arriva e dove sono i soldi", mapOf(WidgetPage.HOME to listOf(
        sec("Entrate"), w("hero"), w("cashflow"), w("period"), w("categories", "kind" to "ENTRATA"),
        sec("Conti"), w("balances"), w("health"),
        sec("Da sistemare"), w("alerts"), w("unassigned"), w("transfer_pairs"), w("pending_amounts"),
    )))
    val SAVING = LayoutPreset("saving", "Risparmio", "Salute del bilancio, margine e consigli per mettere da parte", mapOf(WidgetPage.HOME to listOf(
        sec("Risparmio"), w("health"), w("hero"), w("advice"), w("opps"), w("balances"),
        sec("Da sistemare"), w("alerts"), w("subs"),
    )))
    val MINIMAL = LayoutPreset("minimal", "Minimale", "Solo l'essenziale: cosa puoi spendere, i saldi e gli avvisi", mapOf(WidgetPage.HOME to listOf(
        w("safe"), w("balances"), w("alerts"),
    )))

    val all: List<LayoutPreset> = listOf(BALANCED, SPENDING, INCOME, SAVING, MINIMAL)
    fun byId(id: String): LayoutPreset? = all.firstOrNull { it.id == id }

    /** Le istanze di un preset per una pagina (identificativi `p-N`). */
    fun instances(preset: LayoutPreset, page: WidgetPage): List<WidgetInstance> =
        preset.layouts[page].orEmpty().mapIndexed { i, p -> WidgetInstance("p-$i", p.widgetId, true, p.options) }
}

/** Operazioni sulla composizione di una pagina: tutte restituiscono una lista nuova. */
object LayoutOps {
    /** Composizione da mostrare: quella salvata (ripulita) o, se manca, quella predefinita. */
    fun effective(stored: Map<WidgetPage, List<WidgetInstance>>, page: WidgetPage): List<WidgetInstance> =
        normalize(page, stored[page] ?: default(page))

    fun default(page: WidgetPage): List<WidgetInstance> = LayoutPresets.instances(LayoutPresets.BALANCED, page)

    /** Toglie i widget che l'app non conosce più, gli identificativi doppi e le copie di widget non ripetibili. */
    fun normalize(page: WidgetPage, list: List<WidgetInstance>): List<WidgetInstance> {
        val seenUid = HashSet<String>(); val seenWidget = HashSet<String>()
        return list.filter { i ->
            val spec = WidgetCatalog.spec(i.widgetId)
            spec != null && spec.page == page && seenUid.add(i.uid) && (spec.repeatable || seenWidget.add(i.widgetId))
        }
    }

    fun move(list: List<WidgetInstance>, uid: String, delta: Int): List<WidgetInstance> {
        val from = list.indexOfFirst { it.uid == uid }
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return list
        val m = list.toMutableList(); val x = m.removeAt(from); m.add(to, x)
        return m
    }

    fun setEnabled(list: List<WidgetInstance>, uid: String, on: Boolean): List<WidgetInstance> = list.map { if (it.uid == uid) it.copy(enabled = on) else it }
    fun remove(list: List<WidgetInstance>, uid: String): List<WidgetInstance> = list.filter { it.uid != uid }
    fun setOption(list: List<WidgetInstance>, uid: String, key: String, value: String?): List<WidgetInstance> =
        list.map { if (it.uid == uid) it.copy(options = if (value == null) it.options - key else it.options + (key to value)) else it }

    /** Aggiunge un widget in fondo. Se non è ripetibile e c'è già, lo riaccende invece di duplicarlo. */
    fun add(list: List<WidgetInstance>, widgetId: String, options: Map<String, String> = emptyMap()): List<WidgetInstance> {
        val spec = WidgetCatalog.spec(widgetId) ?: return list
        if (!spec.repeatable) {
            val existing = list.firstOrNull { it.widgetId == widgetId }
            if (existing != null) return setEnabled(list, existing.uid, true)
        }
        val next = (list.mapNotNull { it.uid.removePrefix("w").takeIf { s -> it.uid.startsWith("w") }?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        return list + WidgetInstance("w$next", widgetId, true, options)
    }

    /** I widget della galleria che si possono ancora aggiungere (quelli non ripetibili già presenti non compaiono). */
    fun available(page: WidgetPage, list: List<WidgetInstance>): List<WidgetSpec> =
        WidgetCatalog.forPage(page).filter { s -> s.repeatable || list.none { it.widgetId == s.id } }
}
