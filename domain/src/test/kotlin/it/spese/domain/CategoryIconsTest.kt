package it.spese.domain

import it.spese.core.model.Category
import it.spese.core.model.CategoryIcons
import it.spese.core.model.TxKind
import it.spese.domain.backup.*
import it.spese.domain.classification.DefaultData
import org.junit.Assert.*
import org.junit.Test

class CategoryIconsTest {
    @Test fun `ogni categoria predefinita ha un'icona del catalogo e quelle principali sono distinte`() {
        val keys = DefaultData.categories.map { c ->
            val sys = when (c.name) { DefaultData.FALLBACK_EXPENSE_NAME -> Category.FALLBACK_EXPENSE; DefaultData.FALLBACK_INCOME_NAME -> Category.FALLBACK_INCOME; else -> null }
            CategoryIcons.defaultFor(c.name, c.kind, sys)
        }
        keys.forEach { assertTrue("chiave fuori catalogo: $it", it in CategoryIcons.KEYS) }
        // nessuna categoria di spesa predefinita deve restare con l'icona generica
        DefaultData.categories.filter { it.kind == TxKind.SPESA }.forEach {
            assertNotEquals("${it.name} senza icona specifica", "category", CategoryIcons.defaultFor(it.name, it.kind))
        }
        assertEquals("cart", CategoryIcons.defaultFor("Supermercato e alimentari", TxKind.SPESA))
        assertEquals("restaurant", CategoryIcons.defaultFor("Bar e ristoranti", TxKind.SPESA))
        assertEquals("swap", CategoryIcons.defaultFor("Trasferimenti", TxKind.TRASFERIMENTO))
        assertEquals("phone", CategoryIcons.defaultFor("Telefonia e ricariche", TxKind.SPESA))
        assertEquals("bank", CategoryIcons.defaultFor("Commissioni e bolli", TxKind.SPESA))
        assertEquals("receipt", CategoryIcons.defaultFor("Tasse e bollette", TxKind.SPESA))
        assertEquals("clothes", CategoryIcons.defaultFor("Abbigliamento e sport", TxKind.SPESA))
    }

    @Test fun `senza corrispondenza si usa l'icona del tipo, e una scelta non più nel catalogo viene ignorata`() {
        assertEquals("category", CategoryIcons.defaultFor("Zzz", TxKind.SPESA))
        assertEquals("trend", CategoryIcons.defaultFor("Zzz", TxKind.ENTRATA))
        val c = Category(1, "Zzz", TxKind.SPESA, false, 0, icon = "pets")
        assertEquals("pets", c.iconKey)
        assertEquals("category", c.copy(icon = "inesistente").iconKey)
        assertEquals("category", c.copy(icon = null).iconKey)
    }

    @Test fun `le etichette coprono tutto il catalogo e le chiavi sono uniche`() {
        assertEquals(CategoryIcons.KEYS.size, CategoryIcons.KEYS.toSet().size)
        CategoryIcons.KEYS.forEach { assertNotNull("manca l'etichetta di $it", CategoryIcons.LABELS[it]) }
    }

    @Test fun `l'icona scelta sopravvive al backup e senza icona il formato resta identico a quello dell'HTML`() {
        val env = Fixtures.backupA
        assertFalse(LegacyBackupCodec.encode(env).contains("\"ic\""))
        val withIcon = env.copy(state = env.state.copy(cats = env.state.cats.mapIndexed { i, c -> if (i == 0) c.copy(icon = "pets") else c }))
        val back = LegacyBackupCodec.parse(LegacyBackupCodec.encode(withIcon))
        assertEquals("pets", back.state.cats[0].icon)
        assertNull(back.state.cats[1].icon)
        assertEquals(withIcon.state, back.state)
        // dal backup allo stato di dominio e ritorno
        val snap = LegacyMapper.toDomain(back.state)
        assertEquals("pets", snap.categories.first { it.name == back.state.cats[0].n }.icon)
    }
}
