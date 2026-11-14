package it.spese.core.model

/**
 * Catalogo delle icone di categoria (chiavi stabili, salvate nel database e nel backup) e icona di default dedotta dal nome.
 * Il disegno vero e proprio sta nel design system (`CategoryIconSet`): qui solo le chiavi, così il dominio resta senza Android.
 */
object CategoryIcons {
    /** Chiavi in ordine di presentazione nel selettore. */
    val KEYS: List<String> = listOf(
        "cart", "restaurant", "coffee", "smoking", "car", "fuel", "transit", "flight",
        "home", "garden", "tools", "bolt", "water", "wifi", "phone", "receipt",
        "health", "pharmacy", "fitness", "clothes", "bag", "gift", "pets", "child",
        "family", "school", "movie", "music", "subscriptions", "card", "cash", "bank",
        "shield", "work", "savings", "trend", "swap", "refund", "category", "more",
    )

    /** Etichette brevi (accessibilità e selettore). */
    val LABELS: Map<String, String> = mapOf(
        "cart" to "Spesa", "restaurant" to "Ristoranti", "coffee" to "Bar", "smoking" to "Tabacchi", "car" to "Auto", "fuel" to "Carburante",
        "transit" to "Trasporti", "flight" to "Viaggi", "home" to "Casa", "garden" to "Giardino", "tools" to "Lavori", "bolt" to "Energia",
        "water" to "Acqua", "wifi" to "Internet", "phone" to "Telefono", "receipt" to "Bollette", "health" to "Salute", "pharmacy" to "Farmacia",
        "fitness" to "Sport", "clothes" to "Abbigliamento", "bag" to "Shopping", "gift" to "Regali", "pets" to "Animali", "child" to "Figli",
        "family" to "Famiglia", "school" to "Istruzione", "movie" to "Cinema", "music" to "Musica", "subscriptions" to "Abbonamenti",
        "card" to "Carta", "cash" to "Contanti", "bank" to "Banca", "shield" to "Assicurazioni", "work" to "Lavoro", "savings" to "Risparmi",
        "trend" to "Investimenti", "swap" to "Giroconto", "refund" to "Rimborsi", "category" to "Generica", "more" to "Altro",
    )

    /** Regole nome → icona (la prima che corrisponde vince; confronto sul nome in minuscolo). */
    private val BY_NAME: List<Pair<Regex, String>> = listOf(
        "trasferiment|giroconto" to "swap",
        "stipendio|salario" to "work",
        "assegno|figli|bambin" to "child",
        "contributi|famigli|persone" to "family",
        "supermerc|aliment|spesa" to "cart",
        "\\bbar\\b|ristor|pizz|caff" to "restaurant",
        "tabacc|sigarett" to "smoking",
        "carbur|benzin|distribut" to "fuel",
        "auto|trasport|pedagg|parcheg|treno" to "car",
        "farmac" to "pharmacy",
        "salute|medic|dentist" to "health",
        "giardin|agricolt" to "garden",
        "casa|affitto|mutuo|condomin" to "home",
        "abbigl|vestit|scarpe" to "clothes",
        "sport|palestra" to "fitness",
        "telefon|ricaric|mobile" to "phone",
        "abbonament|streaming" to "subscriptions",
        "shopping|amazon|acquisti" to "bag",
        "contanti|prelievo" to "cash",
        "commission|bolli|banca" to "bank",
        "tasse|imposte|bollett|utenze" to "receipt",
        "assicuraz" to "shield",
        "rimbors" to "refund",
        "viagg|vacanz" to "flight",
        "istruzion|scuola|corsi" to "school",
        "cinema|svago|intratten" to "movie",
        "regal" to "gift",
        "animal" to "pets",
        "risparm|invest" to "savings",
        "altre entrate|entrate" to "trend",
        "altro|varie|diverse" to "more",
    ).map { Regex(it.first) to it.second }

    /** Icona di default per una categoria senza scelta esplicita. */
    fun defaultFor(name: String, kind: TxKind, systemKey: String? = null): String {
        when (systemKey) {
            Category.FALLBACK_EXPENSE -> return "more"
            Category.FALLBACK_INCOME -> return "trend"
        }
        val n = name.lowercase()
        BY_NAME.firstOrNull { it.first.containsMatchIn(n) }?.let { return it.second }
        return when (kind) {
            TxKind.ENTRATA -> "trend"
            TxKind.TRASFERIMENTO -> "swap"
            TxKind.SPESA -> "category"
        }
    }

    /** Icona effettiva: quella scelta se è ancora nel catalogo, altrimenti il default. */
    fun resolve(c: Category): String = c.icon?.takeIf { it in KEYS } ?: defaultFor(c.name, c.kind, c.systemKey)
}
