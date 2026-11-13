package it.spese.domain.classification

import it.spese.core.model.AmountSign
import it.spese.core.model.TxKind
import java.math.BigDecimal

/** Categorie e regole predefinite: identiche a quelle della versione HTML ("Gestione Spese"). */
object DefaultData {
    val PALETTE: List<Long> = listOf(
        0xFF2358D8, 0xFFE0742C, 0xFF16A37F, 0xFFC23B8F, 0xFF8A5CF6, 0xFFD4A017, 0xFF0EA5C6, 0xFFD64545,
        0xFF5B7C2F, 0xFF7A6A5A, 0xFF3A8DDE, 0xFF9B59B6, 0xFFE17B9A, 0xFF4C9A8A, 0xFFA0522D, 0xFF6C757D,
    )

    data class Cat(val name: String, val kind: TxKind, val flexible: Boolean)
    data class RuleDef(val keyword: String, val category: String, val sign: AmountSign = AmountSign.ANY, val min: BigDecimal = BigDecimal.ZERO)

    const val FALLBACK_EXPENSE_NAME = "Altro"
    const val FALLBACK_INCOME_NAME = "Altre entrate"

    val categories: List<Cat> = listOf(
        Cat("Stipendio", TxKind.ENTRATA, false), Cat("Assegno unico INPS", TxKind.ENTRATA, false),
        Cat("Altre entrate", TxKind.ENTRATA, false), Cat("Contributi familiari", TxKind.ENTRATA, false),
        Cat("Trasferimenti", TxKind.TRASFERIMENTO, false),
        Cat("Supermercato e alimentari", TxKind.SPESA, false), Cat("Bar e ristoranti", TxKind.SPESA, true),
        Cat("Tabacchi", TxKind.SPESA, true), Cat("Auto e trasporti", TxKind.SPESA, false),
        Cat("Salute", TxKind.SPESA, false), Cat("Casa e giardino", TxKind.SPESA, true),
        Cat("Abbigliamento e sport", TxKind.SPESA, true), Cat("Telefonia e ricariche", TxKind.SPESA, false),
        Cat("Abbonamenti digitali", TxKind.SPESA, true), Cat("Shopping online", TxKind.SPESA, true),
        Cat("Contanti", TxKind.SPESA, true), Cat("Tasse e bollette", TxKind.SPESA, false),
        Cat("Famiglia e persone", TxKind.SPESA, false), Cat("Commissioni e bolli", TxKind.SPESA, false),
        Cat("Rimborsi", TxKind.SPESA, false), Cat("Altro", TxKind.SPESA, true),
    )

    private fun r(kw: String, cat: String, sg: AmountSign = AmountSign.ANY, min: Int = 0) =
        RuleDef(kw, cat, sg, BigDecimal(min))

    val rules: List<RuleDef> = listOf(
        r("re:commission", "Commissioni e bolli"),
        r("dag costruzioni", "Stipendio"),
        r("re:inps|assegno unico", "Assegno unico INPS"),
        r("revolut", "Trasferimenti"), r("hype", "Trasferimenti"),
        r("re:prelievo", "Supermercato e alimentari", min = 100), r("re:prelievo", "Contanti"),
        r("re:f24|pagopa|enel", "Tasse e bollette"),
        r("re:^bonifico.*\\bspese\\b", "Contributi familiari", AmountSign.POSITIVE),
        r("bollettino", "Tasse e bollette"),
        r("postagiro", "Famiglia e persone", AmountSign.NEGATIVE),
        r("re:^rimborso", "Rimborsi"),
        r("re:distributore|\\beni\\b", "Auto e trasporti"), r("carburanti", "Auto e trasporti"),
        r("nextcharge", "Auto e trasporti"), r("pedaggio", "Auto e trasporti"),
        r("parcheggio", "Auto e trasporti"), r("cotral", "Auto e trasporti"), r("trenitalia", "Auto e trasporti"),
        r("re:caff|\\bbar\\b|pizzeria|donald|gelat|dolce |dolci|sanetti|pasticc|ristor|trattoria|osteria|sfizio", "Bar e ristoranti"),
        r("tabacc", "Tabacchi"),
        r("kena", "Telefonia e ricariche"), r("ho-mobile", "Telefonia e ricariche"),
        r("iliad", "Telefonia e ricariche"), r("very-ricarica", "Telefonia e ricariche"),
        r("re:anthropic|openai|google play|google\\*google|ring basic|prime|nintendo|steam", "Abbonamenti digitali"),
        r("re:amazon|scalapay|klarna", "Shopping online"),
        r("re:decathlon|\\bovs\\b|hm it|akiko", "Abbigliamento e sport"),
        r("re:dentistic|farmacia|dott|ambulatorio", "Salute"),
        r("re:coop|mercato cdc|in's mercato|eurospin|conad|supercasa|incoop|pezzato|hurra", "Supermercato e alimentari"),
        r("re:king home|tedi|cecchini agricoltura|agricoltore|ferrament", "Casa e giardino"),
    )
}
