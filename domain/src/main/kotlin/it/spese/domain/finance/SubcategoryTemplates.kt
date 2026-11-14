package it.spese.domain.finance

/** Idee di sottocategorie per le categorie più comuni (match sul nome, senza distinguere maiuscole): servono solo da suggerimento. */
object SubcategoryTemplates {
    private val templates: List<Pair<List<String>, List<String>>> = listOf(
        listOf("supermercat", "aliment", "spesa") to listOf("Frutta e verdura", "Carne e pesce", "Latticini e uova", "Pane e dolci", "Bevande", "Pulizia casa", "Igiene personale", "Animali"),
        listOf("ristorant", "bar", "mangiare fuori", "svago") to listOf("Ristoranti", "Bar e caffè", "Take away", "Cinema e spettacoli", "Eventi"),
        listOf("trasport", "auto", "carburant", "mobilit") to listOf("Carburante", "Mezzi pubblici", "Parcheggio e pedaggi", "Manutenzione", "Assicurazione", "Taxi e sharing"),
        listOf("casa", "abitazion", "utenz", "bollett") to listOf("Affitto o mutuo", "Luce", "Gas", "Acqua", "Internet e telefono", "Condominio", "Manutenzione casa"),
        listOf("salute", "farmac", "medic") to listOf("Farmaci", "Visite", "Dentista", "Analisi", "Ottico"),
        listOf("shopping", "abbigliament", "vestit") to listOf("Abbigliamento", "Scarpe", "Elettronica", "Casa e arredo", "Regali"),
        listOf("viagg", "vacanz") to listOf("Trasporti", "Alloggio", "Ristoranti", "Attività ed escursioni"),
        listOf("abbonament", "digital") to listOf("Streaming", "Musica", "Cloud e software", "Giornali e riviste"),
        listOf("istruzion", "scuol", "formazion") to listOf("Rette e tasse", "Libri e materiale", "Corsi"),
        listOf("stipend", "entrat", "redditi") to listOf("Stipendio", "Bonus e premi", "Rimborsi", "Affitti incassati"),
    )

    fun suggest(parentName: String): List<String> {
        val n = parentName.lowercase()
        return templates.firstOrNull { (keys, _) -> keys.any { n.contains(it) } }?.second.orEmpty()
    }
}
