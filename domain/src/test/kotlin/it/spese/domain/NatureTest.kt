package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class NatureTest {
    private val cats = listOf(
        Category(1, "Abbonamenti digitali", TxKind.SPESA, true, 0), Category(2, "Supermercato e alimentari", TxKind.SPESA, false, 0),
        Category(3, "Bar e ristoranti", TxKind.SPESA, true, 0), Category(4, "Altro", TxKind.SPESA, true, 0, systemKey = Category.FALLBACK_EXPENSE),
        Category(5, "Stipendio", TxKind.ENTRATA, false, 0),
    )
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long, desc: String) =
        Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), desc, BigDecimal(amount), if (cat == 5L) TxKind.ENTRATA else TxKind.SPESA, cat)
    private val txs = listOf("2026-05-03", "2026-06-03", "2026-07-03", "2026-08-03", "2026-09-04", "2026-10-03").map { tx(it, -13, 1, "Netflix") } +
        listOf(tx("2026-06-10", -80, 2, "Coop"), tx("2026-07-21", -95, 2, "Conad"), tx("2026-09-02", -70, 2, "Coop"),
            tx("2026-06-14", -45, 3, "Pizzeria"), tx("2026-08-30", -60, 3, "Bar Roma"), tx("2026-10-10", -12, 4, "Varie"))

    @Test fun `ricorrenze fisse, spese di base e discrezionali`() {
        val rec = RecurringDetector.detect(txs)
        val s = NatureSuggester.suggest(cats, txs, rec)
        assertEquals(SpendNature.FIXED, s.getValue(1).nature)
        assertEquals(SpendNature.ESSENTIAL, s.getValue(2).nature)
        assertEquals(SpendNature.DISCRETIONARY, s.getValue(3).nature)
        assertEquals(SpendNature.DISCRETIONARY, s.getValue(4).nature)   // "Altro": mai "di base"
        assertFalse(s.containsKey(5L))                                  // solo categorie di spesa
    }

    @Test fun `la ripartizione per natura usa la natura impostata e quella della principale`() {
        val withNature = listOf(cats[0].copy(nature = SpendNature.FIXED), cats[1].copy(nature = SpendNature.ESSENTIAL), cats[2].copy(parentId = 1), cats[3], cats[4]).associateBy { it.id }
        val b = NatureSuggester.breakdown(mapOf(1L to BigDecimal(78), 2L to BigDecimal(245), 3L to BigDecimal(105), 4L to BigDecimal(12), null to BigDecimal(5)), withNature)
        assertEquals(0, BigDecimal(183).compareTo(b[SpendNature.FIXED]))      // 78 + 105 (figlia senza natura propria)
        assertEquals(0, BigDecimal(245).compareTo(b[SpendNature.ESSENTIAL]))
        assertEquals(0, BigDecimal(17).compareTo(b[null]))                    // senza natura: "Altro" + senza categoria
    }

    @Test fun `senza movimenti i suggerimenti si basano sul nome`() {
        val s = NatureSuggester.suggest(cats, emptyList(), emptyList())
        assertEquals(SpendNature.ESSENTIAL, s.getValue(2).nature); assertEquals(SpendNature.DISCRETIONARY, s.getValue(1).nature)
    }

    @Test fun `la natura sopravvive al backup`() {
        val env = Fixtures.backupA
        assertFalse(it.spese.domain.backup.LegacyBackupCodec.encode(env).contains("\"nt\""))
        val st = env.state.copy(cats = env.state.cats.mapIndexed { i, c -> if (i == 0) c.copy(nature = "f") else c })
        val back = it.spese.domain.backup.LegacyBackupCodec.parse(it.spese.domain.backup.LegacyBackupCodec.encode(env.copy(state = st)))
        assertEquals("f", back.state.cats[0].nature)
        val snap = it.spese.domain.backup.LegacyMapper.toDomain(back.state)
        assertEquals(SpendNature.FIXED, snap.categories.first { it.name == st.cats[0].n }.nature)
        assertEquals("f", it.spese.domain.backup.LegacyMapper.fromDomain(snap).cats.first { it.n == st.cats[0].n }.nature)
    }
}
