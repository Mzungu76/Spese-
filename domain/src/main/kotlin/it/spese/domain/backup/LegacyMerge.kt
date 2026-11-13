package it.spese.domain.backup

import it.spese.domain.backup.LegacyBackupCodec.num
import it.spese.domain.backup.LegacyBackupCodec.rsig

/** Elemento che l'unione aggiungerebbe (`isNew`) o aggiornerebbe. */
data class MergeItem(val key: String, val isNew: Boolean) {
    val type: String get() = key.substringBefore(':')
    val name: String get() = if (':' in key) key.substringAfter(':') else ""
}

data class MergePlan(
    val newTx: List<LegacyTx>,
    val items: List<MergeItem>,
    val orderFromRemote: Boolean,
) {
    val isEmpty: Boolean get() = newTx.isEmpty() && items.isEmpty() && !orderFromRemote

    /** Riepilogo leggibile (stesse voci della finestra "Importa backup" della versione HTML). */
    fun summary(): List<String> {
        val out = ArrayList<String>()
        if (newTx.isNotEmpty()) {
            val accs = newTx.map { it.acc }.toSet()
            out.add("Movimenti nuovi: ${newTx.size}" + if (accs.isNotEmpty()) " (conti: ${accs.joinToString(", ")})" else "")
        }
        fun line(type: String, label: String) {
            val n = items.count { it.type == type && it.isNew }; val u = items.count { it.type == type && !it.isNew }
            if (n + u == 0) return
            val nw = if (n > 0) "$n nuov${if (n == 1) "o" else "i"}" else ""
            val up = if (u > 0) "$u aggiornat${if (u == 1) "o" else "i"}" else ""
            out.add("$label: $nw${if (n > 0 && u > 0) ", " else ""}$up")
        }
        line("cat", "Categorie"); line("rule", "Regole"); line("ov", "Assegnazioni manuali"); line("bud", "Budget")
        line("due", "Scadenze"); line("goal", "Obiettivo di risparmio")
        if (orderFromRemote) out.add("Ordine delle regole: preso dal backup più recente")
        return out
    }
}

/**
 * Unione di due stati (locale + backup) con la stessa semantica della versione HTML: i movimenti mancanti vengono
 * aggiunti; per categorie, regole, assegnazioni manuali, budget, scadenze e obiettivo vince la modifica più recente
 * (timestamp `mt`). Le eliminazioni non vengono propagate.
 */
object LegacyMerge {
    fun entities(x: LegacyState): Map<String, String> {
        val e = LinkedHashMap<String, String>()
        x.ov.forEach { (k, v) -> e["ov:$k"] = v }
        x.cats.forEach { c -> e["cat:${c.n}"] = "${c.k.key}|${if (c.f) 1 else 0}|${c.c}|${if (c.active) 1 else 0}" }
        x.rules.forEach { r -> e["rule:${rsig(r)}"] = r.cat }
        x.budget.forEach { (k, v) -> e["bud:$k"] = num(v) }
        x.due.forEach { d -> e["due:${d.id}"] = "${d.name}|${num(d.amt)}|${d.next}|${d.freq.key}" }
        e["goal"] = "${x.goal.mode.key}|${num(x.goal.value)}|${x.goal.months}|${x.goal.income?.let { num(it) } ?: ""}|" +
            x.cuts.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        return e
    }

    fun lastEdit(x: LegacyState): Long = x.mt.values.maxOrNull() ?: 0L

    fun plan(local: LegacyState, remote: LegacyState): MergePlan {
        val re = entities(remote); val le = entities(local)
        val have = local.tx.map { it.id }.toHashSet()
        val seen = HashSet<String>()
        val newTx = remote.tx.filter { it.id !in have && seen.add(it.id) }
        val items = ArrayList<MergeItem>()
        for ((k, v) in re) {
            val rt = remote.mt[k] ?: 0L; val lt = local.mt[k] ?: 0L
            if (k !in le) items.add(MergeItem(k, true))
            else if (le[k] != v && rt > lt) items.add(MergeItem(k, false))
        }
        val remoteSigs = remote.rules.map { rsig(it) }
        val remoteSet = remoteSigs.toSet()
        val localFiltered = local.rules.map { rsig(it) }.filter { it in remoteSet }
        val order = lastEdit(remote) > lastEdit(local) && remoteSigs.joinToString("\n") != localFiltered.joinToString("\n")
        return MergePlan(newTx, items, order)
    }

    fun apply(local: LegacyState, remote: LegacyState, plan: MergePlan): LegacyState {
        var tx = (local.tx + plan.newTx).sortedByDescending { it.d } // sort stabile: a parità di data resta l'ordine
        var cats = local.cats; var rules = local.rules; var due = local.due
        val ov = local.ov.toMutableMap(); val budget = local.budget.toMutableMap()
        var goal = local.goal; var cuts = local.cuts
        val mt = local.mt.toMutableMap()
        for (item in plan.items) {
            val n = item.name
            when (item.type) {
                "ov" -> remote.ov[n]?.let { ov[n] = it }
                "cat" -> remote.cats.firstOrNull { it.n == n }?.let { c ->
                    cats = if (cats.any { it.n == n }) cats.map { if (it.n == n) c else it } else cats + c
                }
                "rule" -> remote.rules.firstOrNull { rsig(it) == n }?.let { r ->
                    rules = if (rules.any { rsig(it) == n }) rules.map { if (rsig(it) == n) it.copy(cat = r.cat) else it }
                    else rules + r.copy(id = (rules.maxOfOrNull { it.id } ?: 0L) + 1)
                }
                "bud" -> remote.budget[n]?.let { budget[n] = it }
                "due" -> remote.due.firstOrNull { it.id == n }?.let { d ->
                    due = if (due.any { it.id == n }) due.map { if (it.id == n) d else it } else due + d
                }
                "goal" -> { goal = remote.goal; cuts = remote.cuts }
            }
            mt[item.key] = remote.mt[item.key] ?: 0L
        }
        if (plan.orderFromRemote) {
            val ord = remote.rules.map { rsig(it) }.distinct()
            val ordered = ord.mapNotNull { k -> rules.firstOrNull { rsig(it) == k } }
            rules = ordered + rules.filter { rsig(it) !in ord }
        }
        return LegacyState(tx, cats, rules, due, ov, budget, goal, cuts, mt)
    }
}
