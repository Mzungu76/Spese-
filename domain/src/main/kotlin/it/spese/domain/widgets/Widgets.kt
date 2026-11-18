package it.spese.domain.widgets

import kotlinx.coroutines.flow.Flow

/** Le pagine che si possono comporre con i widget. */
enum class WidgetPage(val label: String) { HOME("Home"), TRANSACTIONS("Movimenti"), GOALS("Obiettivi"), NETWORTH("Patrimonio") }

/** Come i widget sono raggruppati nella galleria. */
enum class WidgetGroup(val label: String) { TODAY("Oggi"), MONTH("Spese ed entrate"), TODO("Da sistemare"), SAVINGS("Obiettivi"), WEALTH("Patrimonio"), LIST("Movimenti"), LAYOUT("Struttura") }

/** Un widget che l'app sa mostrare: un blocco con un solo compito. `repeatable` = se ne possono mettere più copie (es. i titoli di sezione). */
data class WidgetSpec(
    val id: String, val title: String, val description: String, val group: WidgetGroup, val pages: Set<WidgetPage>, val repeatable: Boolean = false,
    /** Non si può nascondere né togliere (es. l'elenco dei movimenti): senza, la pagina non avrebbe senso. */
    val required: Boolean = false,
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
    private val HOME = setOf(WidgetPage.HOME)
    private fun h(id: String, title: String, desc: String, g: WidgetGroup, repeatable: Boolean = false) = WidgetSpec(id, title, desc, g, HOME, repeatable)
    private fun p(page: WidgetPage, id: String, title: String, desc: String, g: WidgetGroup, required: Boolean = false) = WidgetSpec(id, title, desc, g, setOf(page), false, required)

    val specs: List<WidgetSpec> = listOf(
        WidgetSpec(SECTION, "Titolo di sezione", "Una riga di titolo per raggruppare i widget", WidgetGroup.LAYOUT, WidgetPage.entries.toSet(), repeatable = true),
        // Home
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
        // Movimenti
        p(WidgetPage.TRANSACTIONS, "tx_filters", "Ricerca e filtri", "Cerca un movimento e filtra per periodo, conto, categoria", WidgetGroup.LIST),
        p(WidgetPage.TRANSACTIONS, "tx_view", "Tutti · Verifica · Scontrini", "Il selettore per vedere tutti i movimenti, quelli da controllare o quelli con scontrino", WidgetGroup.LIST),
        p(WidgetPage.TRANSACTIONS, "tx_summary", "Totali dell'elenco", "Quanti movimenti, entrate, spese e il netto dei giroconti", WidgetGroup.LIST),
        p(WidgetPage.TRANSACTIONS, "tx_classify", "Da classificare", "Quanti movimenti non hanno ancora una categoria", WidgetGroup.LIST),
        p(WidgetPage.TRANSACTIONS, "tx_list", "Elenco dei movimenti", "I movimenti, raggruppati per giorno", WidgetGroup.LIST, required = true),
        // Obiettivi
        p(WidgetPage.GOALS, "goals_intro", "Come funzionano", "Una breve spiegazione degli obiettivi", WidgetGroup.SAVINGS),
        p(WidgetPage.GOALS, "goals_summary", "Da mettere da parte ogni mese", "La quota mensile totale e i pulsanti per i budget", WidgetGroup.SAVINGS),
        p(WidgetPage.GOALS, "goals_list", "I tuoi obiettivi", "Ogni obiettivo con il suo avanzamento", WidgetGroup.SAVINGS),
        // Patrimonio
        p(WidgetPage.NETWORTH, "nw_total", "Patrimonio totale", "Quanto hai in tutto, tra liquidità, investimenti e conti di altri", WidgetGroup.WEALTH),
        p(WidgetPage.NETWORTH, "nw_trend", "Andamento", "Come è cambiato il patrimonio negli ultimi mesi", WidgetGroup.WEALTH),
        p(WidgetPage.NETWORTH, "nw_fund", "Fondo di emergenza", "Quanti mesi di spese essenziali copri", WidgetGroup.WEALTH),
        p(WidgetPage.NETWORTH, "nw_margin", "Margine investibile", "Quanto potresti investire senza toccare il fondo", WidgetGroup.WEALTH),
        p(WidgetPage.NETWORTH, "nw_accounts", "Conti", "Il saldo di ogni conto, con il valore degli investimenti", WidgetGroup.WEALTH),
    )

    private val byId = specs.associateBy { it.id }
    fun spec(id: String): WidgetSpec? = byId[id]
    fun forPage(page: WidgetPage): List<WidgetSpec> = specs.filter { page in it.pages }
}

/** Un widget in una composizione predefinita. */
data class PresetItem(val widgetId: String, val options: Map<String, String> = emptyMap())

/** Una composizione pronta (un «setup»): per pagina, l'elenco ordinato dei widget. */
data class LayoutPreset(val id: String, val title: String, val description: String, val layouts: Map<WidgetPage, List<PresetItem>>)

object LayoutPresets {
    private fun sec(t: String) = PresetItem(WidgetCatalog.SECTION, mapOf("title" to t))
    private fun w(id: String, vararg o: Pair<String, String>) = PresetItem(id, o.toMap())
    private val H = WidgetPage.HOME; private val T = WidgetPage.TRANSACTIONS; private val G = WidgetPage.GOALS; private val N = WidgetPage.NETWORTH

    /** Quella che l'app mostrava prima dei widget: resta la composizione di partenza. */
    val BALANCED = LayoutPreset("balanced", "Equilibrato", "Tutto, nell'ordine di sempre", mapOf(
        H to listOf(
            sec("Oggi"), w("health"), w("safe"), w("advice"),
            sec("Questo mese"), w("hero"), w("cashflow"), w("period"), w("balances"), w("pace"), w("watch"), w("opps"), w("categories"),
            sec("Da sistemare"), w("alerts"), w("unassigned"), w("transfer_pairs"), w("pending_amounts"), w("notif_review"), w("subs"),
        ),
        T to listOf(w("tx_filters"), w("tx_view"), w("tx_summary"), w("tx_classify"), w("tx_list")),
        G to listOf(w("goals_intro"), w("goals_summary"), w("goals_list")),
        N to listOf(w("nw_total"), w("nw_trend"), w("nw_fund"), w("nw_margin"), sec("Conti"), w("nw_accounts")),
    ))
    val SPENDING = LayoutPreset("spending", "Focus spese", "Quanto puoi spendere, il ritmo e dove vanno i soldi", mapOf(
        H to listOf(
            sec("Oggi"), w("safe"), w("pace"), w("period"),
            sec("Dove vanno i soldi"), w("categories", "kind" to "SPESA"), w("watch"), w("opps"), w("cashflow"),
            sec("Da sistemare"), w("alerts"), w("subs"), w("unassigned"), w("transfer_pairs"),
        ),
        T to listOf(w("tx_filters"), w("tx_summary"), w("tx_view"), w("tx_classify"), w("tx_list")),
        G to listOf(w("goals_summary"), w("goals_list")),
        N to listOf(w("nw_total"), w("nw_accounts"), w("nw_fund")),
    ))
    val INCOME = LayoutPreset("income", "Focus entrate", "Quanto incassi, da dove arriva e dove sono i soldi", mapOf(
        H to listOf(
            sec("Entrate"), w("hero"), w("cashflow"), w("period"), w("categories", "kind" to "ENTRATA"),
            sec("Conti"), w("balances"), w("health"),
            sec("Da sistemare"), w("alerts"), w("unassigned"), w("transfer_pairs"), w("pending_amounts"),
        ),
        T to listOf(w("tx_filters"), w("tx_summary"), w("tx_list")),
        G to listOf(w("goals_summary"), w("goals_list")),
        N to listOf(w("nw_total"), w("nw_trend"), sec("Conti"), w("nw_accounts")),
    ))
    val SAVING = LayoutPreset("saving", "Risparmio", "Salute del bilancio, obiettivi, margine e consigli per mettere da parte", mapOf(
        H to listOf(
            sec("Risparmio"), w("health"), w("hero"), w("advice"), w("opps"), w("balances"),
            sec("Da sistemare"), w("alerts"), w("subs"),
        ),
        T to listOf(w("tx_summary"), w("tx_filters"), w("tx_view"), w("tx_list")),
        G to listOf(w("goals_summary"), w("goals_list"), w("goals_intro")),
        N to listOf(w("nw_total"), w("nw_fund"), w("nw_margin"), w("nw_trend"), sec("Conti"), w("nw_accounts")),
    ))
    val MINIMAL = LayoutPreset("minimal", "Minimale", "Solo l'essenziale in ogni pagina", mapOf(
        H to listOf(w("safe"), w("balances"), w("alerts")),
        T to listOf(w("tx_filters"), w("tx_list")),
        G to listOf(w("goals_list")),
        N to listOf(w("nw_total"), w("nw_accounts")),
    ))

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
        val kept = list.filter { i ->
            val spec = WidgetCatalog.spec(i.widgetId)
            spec != null && page in spec.pages && seenUid.add(i.uid) && (spec.repeatable || seenWidget.add(i.widgetId))
        }.map { i -> if (WidgetCatalog.spec(i.widgetId)?.required == true) i.copy(enabled = true) else i }
        // i widget indispensabili (es. l'elenco dei movimenti) non possono mancare
        val missing = WidgetCatalog.forPage(page).filter { it.required && kept.none { k -> k.widgetId == it.id } }
        return kept + missing.map { WidgetInstance("r-${it.id}", it.id) }
    }

    fun move(list: List<WidgetInstance>, uid: String, delta: Int): List<WidgetInstance> {
        val from = list.indexOfFirst { it.uid == uid }
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return list
        val m = list.toMutableList(); val x = m.removeAt(from); m.add(to, x)
        return m
    }

    private fun isRequired(list: List<WidgetInstance>, uid: String) = list.firstOrNull { it.uid == uid }?.let { WidgetCatalog.spec(it.widgetId)?.required } == true
    fun setEnabled(list: List<WidgetInstance>, uid: String, on: Boolean): List<WidgetInstance> =
        if (isRequired(list, uid)) list else list.map { if (it.uid == uid) it.copy(enabled = on) else it }
    fun remove(list: List<WidgetInstance>, uid: String): List<WidgetInstance> = if (isRequired(list, uid)) list else list.filter { it.uid != uid }
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
