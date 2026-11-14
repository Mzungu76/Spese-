package it.spese.domain.finance

import it.spese.core.model.Category
import java.math.BigDecimal

/** Gerarchia delle categorie: un solo livello (categoria principale → sottocategorie). */
object CategoryTree {
    /** Id della categoria principale: la stessa, oppure il padre se esiste ancora. */
    fun rootId(id: Long?, byId: Map<Long, Category>): Long? {
        if (id == null) return null
        val p = byId[id]?.parentId
        return if (p != null && byId.containsKey(p)) p else id
    }

    fun children(id: Long, categories: Collection<Category>): List<Category> = categories.filter { it.parentId == id }

    /** La categoria e tutte le sue sottocategorie. */
    fun withChildren(id: Long, categories: Collection<Category>): Set<Long> = children(id, categories).map { it.id }.toSet() + id

    /** Somma i valori delle sottocategorie in quelli della categoria principale. */
    fun rollup(values: Map<Long?, BigDecimal>, byId: Map<Long, Category>): Map<Long?, BigDecimal> {
        val out = LinkedHashMap<Long?, BigDecimal>()
        values.forEach { (id, v) -> out.merge(rootId(id, byId), v) { a, b -> a + b } }
        return out
    }
}
