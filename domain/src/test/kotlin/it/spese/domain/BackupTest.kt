package it.spese.domain

import it.spese.core.model.AmountSign
import it.spese.core.model.DueFrequency
import it.spese.domain.backup.*
import java.math.BigDecimal
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    @Test fun `un backup prodotto dall'HTML viene letto integralmente`() {
        val env = Fixtures.backupA
        assertEquals("gestione-spese", LegacyBackupCodec.APP)
        assertEquals(223, env.state.tx.size)
        assertEquals(21, env.state.cats.size)
        assertEquals(32, env.state.rules.size)           // 31 predefinite + 1 aggiunta dall'utente
        assertEquals(2, env.state.due.size)
        assertEquals(2, env.state.ov.size)
        assertEquals(BigDecimal("250"), env.state.budget["Supermercato e alimentari"]!!.stripTrailingZeros().setScale(0))
        assertEquals(BigDecimal("3600"), env.state.goal.value.stripTrailingZeros().setScale(0))   // nell'HTML è una stringa "3600"
        assertEquals(30, env.state.cuts["Bar e ristoranti"])
        assertTrue(env.state.mt.isNotEmpty())
        assertEquals(emptyList<BackupIssue>(), LegacyBackupCodec.validate(env).filter { it.error })
    }

    @Test fun `importi esatti, senza errori da virgola mobile`() {
        val total = Fixtures.backupA.state.tx.fold(BigDecimal.ZERO) { a, t -> a + t.a }
        val expected = Fixtures.golden["tx"]!!.jsonArray.fold(BigDecimal.ZERO) { a, t -> a + BigDecimal(t.jsonObject["a"]!!.s()).setScale(2, java.math.RoundingMode.HALF_UP) }
        assertEquals(expected, total)
        assertTrue(Fixtures.backupA.state.tx.all { it.a.scale() == 2 })
    }

    @Test fun `codifica e rilettura senza perdite, formato compatibile con l'HTML`() {
        val env = Fixtures.backupA
        val json = LegacyBackupCodec.encode(env)
        val back = LegacyBackupCodec.parse(json)
        assertEquals(env.state, back.state)
        // struttura attesa dall'HTML: app, v:2 e data.{tx,cats,rules,due,ov,budget,goal,cuts,mt}
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        assertEquals("gestione-spese", o["app"]!!.s()); assertEquals("2", o["v"]!!.s())
        val data = o["data"]!!.jsonObject
        listOf("tx", "cats", "rules", "due", "ov", "budget", "goal", "cuts", "mt").forEach { assertTrue(it, data.containsKey(it)) }
        assertEquals("Intesa", data["tx"]!!.jsonArray[0].jsonObject["acc"]!!.s())
    }

    @Test fun `formati non validi vengono rifiutati con errore chiaro`() {
        assertThrows(BackupFormatException::class.java) { LegacyBackupCodec.parse("non json") }
        assertThrows(BackupFormatException::class.java) { LegacyBackupCodec.parse("{\"x\":1}") }
        assertThrows(BackupFormatException::class.java) { LegacyBackupCodec.parse("[1,2]") }
    }

    @Test fun `backup senza wrapper e con campi mancanti`() {
        val env = LegacyBackupCodec.parse("""{"tx":[{"d":"2024-01-02","a":-5.5,"t":"x"}],"cats":[{"n":"Altro","k":"spesa","f":1,"c":"#888"}],"rules":[]}""")
        assertEquals("", env.state.tx[0].acc)               // senza conto indicato: nessun conto di ripiego, il movimento resta senza conto
        assertEquals("2024-01-02|2024-01-02|-5.50|x".let { "|$it#1" }, env.state.tx[0].id)
        assertEquals(0, BigDecimal.ZERO.compareTo(env.state.goal.value))      // senza obiettivo nel backup: nessun risparmio di default
    }

    @Test fun `validazione segnala regole su categorie inesistenti e regex errate`() {
        val env = LegacyBackupCodec.parse("""{"tx":[{"id":"a","d":"2024-01-02","a":-5.5,"t":"x"},{"id":"a","d":"2024-01-02","a":-5.5,"t":"x"}],
          "cats":[{"n":"Altro","k":"spesa","f":1,"c":"#888"}],"rules":[{"kw":"re:([","cat":"Altro"},{"kw":"a","cat":"Fantasma"}],"ov":{"a":"Fantasma"}}""")
        val msgs = LegacyBackupCodec.validate(env).map { it.message }
        assertTrue(msgs.any { it.contains("duplicati") }); assertTrue(msgs.any { it.contains("inesistenti") }); assertTrue(msgs.any { it.contains("regolare") })
    }

    @Test fun `unione identica alla versione HTML`() {
        val local = Fixtures.backupA.state; val remote = Fixtures.backupB.state
        val plan = LegacyMerge.plan(local, remote)
        assertEquals(Fixtures.golden["mergeSummary"]!!.jsonArray.map { it.s() }, plan.summary())
        assertEquals(Fixtures.golden["mergeNewTx"]!!.jsonArray.map { it.s() }, plan.newTx.map { it.id })
        assertEquals(Fixtures.golden["mergeItems"]!!.jsonArray.map { it.jsonObject["k"]!!.s() to (it.jsonObject["n"]!!.s() == "1") }.toSet(),
            plan.items.map { it.key to it.isNew }.toSet())
        assertTrue(plan.orderFromRemote)

        val merged = LegacyMerge.apply(local, remote, plan)
        val m = Fixtures.golden["merged"]!!.jsonObject
        assertEquals(m["txIds"]!!.jsonArray.map { it.s() }.toSet(), merged.tx.map { it.id }.toSet())
        assertEquals(m["cats"]!!.jsonArray.map { it.jsonObject["n"]!!.s() }, merged.cats.map { it.n })
        assertEquals(m["cats"]!!.jsonArray.first().jsonObject["c"]!!.s(), merged.cats.first().c)
        assertEquals(m["rules"]!!.jsonArray.map { it.s() }, merged.rules.map { LegacyBackupCodec.rsig(it) + "=>" + it.cat })
        assertEquals(m["budget"]!!.jsonObject.mapValues { it.value.d().stripTrailingZeros() }, merged.budget.mapValues { it.value.stripTrailingZeros() })
        assertEquals(m["due"]!!.jsonArray.map { it.jsonObject["id"]!!.s() }, merged.due.map { it.id })
        assertMoney("affitto aggiornato", BigDecimal(650), merged.due.first { it.id == "dtest2" }.amt)
        assertEquals(DueFrequency.MONTHLY, merged.due.first { it.id == "dtest2" }.freq)
        assertEquals(m["cuts"]!!.jsonObject.mapValues { it.value.s().toInt() }, merged.cuts)
        assertEquals("m", merged.goal.mode.key); assertMoney("obiettivo", BigDecimal(500), merged.goal.value)
        assertMoney("entrate attese", BigDecimal(2000), merged.goal.income!!)
        assertEquals(m["ov"]!!.jsonObject.mapValues { it.value.s() }, merged.ov)

        // classificazione dei movimenti dopo l'unione = HTML
        val snap = LegacyMapper.toDomain(merged)
        val names = snap.categories.associate { it.id to it.name }
        val got = snap.transactions.associate { it.externalId to Triple(names[it.categoryId], it.kind.key, it.manualCategory) }
        m["cl"]!!.jsonArray.forEach { e ->
            val a = e.jsonArray
            assertEquals(a[0].s(), Triple(a[1].s(), a[2].s(), a[3].s().toBoolean()), got[a[0].s()])
        }
    }

    @Test fun `unione senza differenze e idempotente`() {
        val s = Fixtures.backupA.state
        val p = LegacyMerge.plan(s, s)
        assertTrue(p.isEmpty)
        val merged = LegacyMerge.apply(s, Fixtures.backupB.state, LegacyMerge.plan(s, Fixtures.backupB.state))
        assertTrue(LegacyMerge.plan(merged, Fixtures.backupB.state).newTx.isEmpty())
        assertTrue(LegacyMerge.plan(merged, Fixtures.backupB.state).items.isEmpty())
    }

    @Test fun `la modifica piu vecchia non sovrascrive quella piu recente`() {
        val local = Fixtures.backupB.state; val remote = Fixtures.backupA.state   // locale più recente
        val plan = LegacyMerge.plan(local, remote)
        assertTrue(plan.items.none { !it.isNew })
        assertFalse(plan.orderFromRemote)
    }

    @Test fun `conversione dominio-backup-dominio conserva i dati`() {
        val snap = Fixtures.snapshotA
        val legacy = LegacyMapper.fromDomain(snap, Fixtures.backupA.state.mt)
        val again = LegacyMapper.toDomain(legacy)
        assertEquals(snap.transactions.size, again.transactions.size)
        assertEquals(snap.transactions.map { it.externalId to it.categoryId }.toSet().size, again.transactions.size)
        assertEquals(Fixtures.backupA.state.ov, legacy.ov)
        assertEquals(Fixtures.backupA.state.budget.mapValues { it.value.stripTrailingZeros() }, legacy.budget.mapValues { it.value.stripTrailingZeros() })
        assertEquals(snap.goal, again.goal)
        assertEquals(AmountSign.NEGATIVE, legacy.rules.first { it.kw == "postagiro" }.sg)
    }
}
