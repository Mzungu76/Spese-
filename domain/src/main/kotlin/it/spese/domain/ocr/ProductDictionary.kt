package it.spese.domain.ocr

import it.spese.core.model.Category
import it.spese.core.model.TxKind

/** Proposta per una voce di scontrino: una categoria già esistente, oppure il nome di una sottocategoria da creare (con il consenso dell'utente). */
data class ItemSuggestion(val categoryId: Long?, val newName: String?)

/**
 * Da «LATTE ALTA DIGERIBIL» a «Latticini e uova»: un piccolo dizionario di prodotti da supermercato che propone la sottocategoria
 * per ogni voce. Vale solo come proposta: la memoria delle scelte dell'utente ha la precedenza e ogni riga si può cambiare.
 * I gruppi sono in ordine di precedenza (i più specifici per primi: «sugo alla ricotta» è dispensa, non latticini).
 */
object ProductDictionary {
    private val groups: List<Pair<String, List<String>>> = listOf(
        "Casa e pulizia" to listOf("detersivo", "det", "sacchi", "pattume", "ammorbidente", "candeggina", "spugna", "spugne", "scottex", "igienica", "guanti", "dosatore", "lavastoviglie", "sgrassatore", "carta casa"),
        "Igiene personale" to listOf("bagnoschiuma", "shampoo", "dentifricio", "sapone", "deodorante", "assorbenti", "rasoio", "balsamo", "docciaschiuma"),
        "Animali" to listOf("gatto", "gatti", "cane", "cani", "crocchette", "croccantini", "bocconcini", "lettiera", "sabbia", "petfood", "pet"),
        "Bevande" to listOf("bev", "acqua", "birra", "vino", "succo", "cola", "aranciata", "cappucc", "caffe", "tisana", "spumante", "prosecco", "bibita", "limonata", "fanta", "sprite", "tonica", "energy", "chinotto"),
        "Snack" to listOf("patatine", "chips", "noccioline", "snack", "popcorn", "arachidi"),
        "Pasta e dispensa" to listOf("pasta", "spaghetti", "penne", "fusilli", "ravioli", "gnocchi", "tortellini", "riso", "passata", "polpa", "pelati", "sugo", "lenticchie", "fagioli", "ceci", "legumi", "farina", "zucchero", "sale", "olio", "aceto", "dadi", "brodo", "tonno", "conserva", "couscous", "pesto", "maionese", "ketchup", "senape", "salsa", "spezie", "lievito", "pangrattato", "cacao", "mais", "olive", "capperi", "cornflakes", "orzo", "farro"),
        "Piatti pronti" to listOf("nuggets", "cotoletta", "pizza", "surgelati", "surgelato", "lasagne", "minestrone", "polpette"),
        "Dolci e colazione" to listOf("croissant", "cornetto", "krapfen", "savoiardi", "nutella", "frollini", "biscotti", "merendine", "cioccolato", "cioccolata", "pancake", "marmellata", "cereali", "crostata", "torta", "brioche", "wafer", "gelato", "caramelle", "budino", "biscotto", "merenda", "plumcake", "pandoro", "panettone", "colomba"),
        "Pane e panificati" to listOf("pane", "focacc", "panini", "crostino", "crostini", "grissini", "cracker", "taralli", "piadina", "pancarre"),
        "Latticini e uova" to listOf("latte", "burro", "mascarpone", "edam", "grana", "parmigiano", "mozzarella", "yogurt", "formaggio", "formaggi", "ricotta", "panna", "uova", "uovo", "scamorza", "pecorino", "fontina"),
        "Salumi e carne" to listOf("prosciutto", "salame", "mortadella", "bresaola", "speck", "pollo", "carne", "macinato", "salsiccia", "bistecca", "tacchino", "wurstel", "affettato", "hamburger", "pancetta", "guanciale", "cotechino", "salumi"),
        "Pesce" to listOf("salmone", "merluzzo", "pesce", "gamberi", "orata", "spigola", "baccala", "polpo", "calamari", "cozze", "vongole"),
        "Frutta e verdura" to listOf("frutta", "verdura", "mele", "mela", "banane", "banana", "arance", "arancia", "limoni", "limone", "pomodori", "insalata", "zucchine", "zucchina", "patate", "carote", "cipolle", "aglio", "spinaci", "broccoli", "fragole", "uva", "pere", "pera", "kiwi", "melanzane", "peperoni", "funghi"),
    )

    /** Tutte le parole del dizionario (minuscole): servono anche a riparare le parole storpiate dall'OCR. */
    val allKeywords: Set<String> by lazy { groups.flatMap { it.second }.toSet() }

    /** Nome del gruppo (= sottocategoria proposta) per una voce, oppure null se non si riconosce il prodotto. */
    fun groupOf(description: String): String? {
        val tokens = ItemMemory.key(description).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return groups.firstOrNull { (_, kws) -> kws.any { kw -> tokens.any { t -> matches(t, kw) } } }?.first
    }

    /** Parola uguale, parola che inizia come la chiave («formaggini»), o abbreviazione dello scontrino che inizia la chiave («crocc» → «crocchette»). */
    private fun matches(t: String, kw: String): Boolean =
        t == kw || (kw.length >= 5 && t.startsWith(kw)) || (t.length >= 5 && t !in allKeywords && kw.length > t.length && kw.startsWith(t))

    /** Gruppi alimentari: una spesa in cui prevalgono fa pensare alla categoria «Supermercato», non a quella data dal nome del negozio. */
    val foodGroups: Set<String> = setOf("Pasta e dispensa", "Piatti pronti", "Dolci e colazione", "Pane e panificati", "Latticini e uova", "Salumi e carne", "Pesce", "Frutta e verdura", "Bevande", "Snack")

    val groupNames: List<String> get() = groups.map { it.first }
}

object ItemSuggester {
    private fun nameKey(s: String) = ItemMemory.key(s)

    /**
     * Per ogni voce: 1) la scelta ricordata dall'utente; 2) il gruppo del dizionario, cercando una categoria con quel nome
     * (meglio se figlia di [parentId]); 3) se non esiste e [parentId] è noto, il nome da creare. Solo spese.
     */
    fun suggest(descriptions: List<String>, memory: Map<String, Long>, categories: List<Category>, parentId: Long?): List<ItemSuggestion> {
        val active = categories.filter { it.active && it.kind == TxKind.SPESA && it.systemKey == null }
        val valid = categories.filter { it.active && it.kind != TxKind.TRASFERIMENTO }.map { it.id }.toSet()
        val remembered = ItemMemory.suggest(descriptions, memory, valid)
        return descriptions.mapIndexed { i, d ->
            remembered[i]?.let { return@mapIndexed ItemSuggestion(it, null) }
            val group = ProductDictionary.groupOf(d) ?: return@mapIndexed ItemSuggestion(null, null)
            val gk = nameKey(group); val first = gk.substringBefore(' ')
            fun same(c: Category) = nameKey(c.name).let { it == gk || it.substringBefore(' ') == first }
            val existing = active.firstOrNull { it.parentId == parentId && parentId != null && same(it) } ?: active.firstOrNull { same(it) }
            if (existing != null) ItemSuggestion(existing.id, null)
            else if (parentId != null) ItemSuggestion(null, group) else ItemSuggestion(null, null)
        }
    }
}

/** Sceglie la categoria principale in cui creare le sottocategorie. */
object ParentChooser {
    private val FOOD_NAMES = listOf("supermercat", "aliment", "spesa", "cibo", "grocer", "viveri")

    /**
     * Se la maggior parte delle voci è cibo, la principale è quella che ha un nome da «Supermercato / Alimentari / Spesa» (anche se le regole
     * davano una categoria diversa, magari per il nome del negozio); altrimenti resta [fallbackId].
     */
    fun choose(descriptions: List<String>, categories: List<Category>, fallbackId: Long?): Long? {
        val groups = descriptions.mapNotNull { ProductDictionary.groupOf(it) }
        val food = groups.count { it in ProductDictionary.foodGroups }
        if (descriptions.isEmpty() || food * 2 < descriptions.size) return fallbackId
        val tops = categories.filter { it.active && it.kind == TxKind.SPESA && it.systemKey == null && it.parentId == null }
        val key = { c: Category -> ItemMemory.key(c.name) }
        return FOOD_NAMES.firstNotNullOfOrNull { f -> tops.firstOrNull { c -> key(c).contains(f) } }?.id ?: fallbackId
    }
}
