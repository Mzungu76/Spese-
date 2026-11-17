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

    /** Natura della categoria; se manca (tipico delle sottocategorie) quella della principale. */
    fun rootNature(id: Long?, byId: Map<Long, Category>): it.spese.core.model.SpendNature? {
        val c = id?.let { byId[it] } ?: return null
        return c.nature ?: c.parentId?.let { byId[it]?.nature }
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

    /** "Casa › Bollette" per le sottocategorie, il solo nome per le principali. */
    fun label(c: Category, byId: Map<Long, Category>): String = c.parentId?.let { byId[it] }?.let { "${it.name} › ${c.name}" } ?: c.name

    /** Categorie ordinate come l'albero (principale seguita dalle sue sottocategorie), solo quelle attive. */
    fun ordered(categories: Collection<Category>): List<Category> {
        val active = categories.filter { it.active }
        val ids = active.map { it.id }.toSet()
        val roots = active.filter { it.parentId == null || it.parentId !in ids }
        return roots.flatMap { r -> listOf(r) + active.filter { it.parentId == r.id } }
    }
}
