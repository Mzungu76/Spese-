package it.spese.domain.export

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.domain.classification.merchant
import it.spese.domain.finance.StatsEngine
import it.spese.domain.importing.RawRow
import it.spese.domain.importing.RawSheet
import it.spese.domain.importing.XlsxWriter

/** Esportazioni CSV ed Excel. Il CSV è un'estensione del formato della versione HTML e può essere reimportato. */
object Exporters {
    const val CSV_HEADER = "Data;Valuta;Conto;Importo;Categoria;Descrizione;Tipo;Esercente;Note"

    private fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""

    fun csv(list: List<Transaction>, accountNames: Map<Long, String>, categories: Map<Long, Category>): String =
        "﻿" + CSV_HEADER + "\n" + list.joinToString("\n") { x ->
            listOf(x.bookingDate.toString(), x.valueDate.toString(), q(accountNames[x.accountId] ?: ""), Money.csv(x.amount),
                q(categories[x.categoryId]?.name ?: ""), q(x.description), x.kind.key, q(x.merchant), q(x.notes)).joinToString(";")
        }

    fun xlsx(list: List<Transaction>, accountNames: Map<Long, String>, categories: Map<Long, Category>): ByteArray {
        val tx = mutableListOf<RawRow>(listOf("Data", "Valuta", "Conto", "Importo", "Categoria", "Descrizione", "Tipo", "Esercente", "Note"))
        list.forEach { x ->
            tx.add(listOf(x.bookingDate, x.valueDate, accountNames[x.accountId] ?: "", XlsxWriter.Amount(x.amount),
                categories[x.categoryId]?.name ?: "", x.description, x.kind.label, x.merchant, x.notes))
        }
        val cats = mutableListOf<RawRow>(listOf("Categoria", "Tipo", "Comprimibile", "Budget mensile", "Attiva"))
        categories.values.sortedBy { it.sortOrder }.forEach { c ->
            cats.add(listOf(c.name, c.kind.label, if (c.flexible) "sì" else "no", c.budgetMonthly?.let { XlsxWriter.Amount(it) }, if (c.active) "sì" else "no"))
        }
        val s = StatsEngine.compute(list)
        val mon = mutableListOf<RawRow>(listOf("Mese", "Entrate", "Spese", "Saldo"))
        StatsEngine.allMonths(s.byMonth).forEach { m ->
            val b = s.byMonth[m]
            mon.add(listOf(m.toString(), XlsxWriter.Amount(b?.income ?: java.math.BigDecimal.ZERO), XlsxWriter.Amount(b?.expense ?: java.math.BigDecimal.ZERO), XlsxWriter.Amount(b?.net ?: java.math.BigDecimal.ZERO)))
        }
        return XlsxWriter.toBytes(listOf(RawSheet("Movimenti", tx), RawSheet("Categorie", cats), RawSheet("Riepilogo mensile", mon)))
    }
}
