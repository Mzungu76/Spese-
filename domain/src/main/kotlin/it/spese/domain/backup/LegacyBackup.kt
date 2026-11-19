package it.spese.domain.backup

import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import it.spese.core.model.AmountSign
import it.spese.core.model.DueFrequency
import it.spese.core.model.GoalMode
import it.spese.core.model.SpendNature
import it.spese.core.model.TxKind
import it.spese.domain.importing.DateParser
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Modello dello stato dell'applicazione nel formato di backup della versione HTML ("gestione-spese", v2).
 * Le categorie sono identificate per nome, come nell'originale. Il modello è il ponte tra il database nativo
 * e i file JSON: backup, ripristino, unione, sincronizzazione e annullamento usano tutti questa struttura.
 */
data class LegacyTx(
    val id: String, val d: LocalDate, val v: LocalDate, val a: BigDecimal, val t: String, val acc: String,
    val notes: String = "", val merchant: String? = null, val origin: String? = null, val doc: String? = null,
)
data class LegacyCat(val n: String, val k: TxKind, val f: Boolean, val c: String, val active: Boolean = true, val icon: String? = null, val parent: String? = null, val nature: String? = null)
data class LegacyRule(val id: Long, val kw: String, val cat: String, val sg: AmountSign, val min: BigDecimal, val max: BigDecimal)
data class LegacyDue(val id: String, val name: String, val amt: BigDecimal, val next: LocalDate, val freq: DueFrequency)
data class LegacyGoal(val mode: GoalMode, val value: BigDecimal, val months: Int, val income: BigDecimal?)

data class LegacyState(
    val tx: List<LegacyTx>,
    val cats: List<LegacyCat>,
    val rules: List<LegacyRule>,
    val due: List<LegacyDue>,
    /** Assegnazioni manuali: id movimento -> nome categoria. */
    val ov: Map<String, String>,
    val budget: Map<String, BigDecimal>,
    val goal: LegacyGoal,
    val cuts: Map<String, Int>,
    /** Timestamp (ms) dell'ultima modifica per entità, chiave `tipo:nome`. */
    val mt: Map<String, Long>,
)

/** Estensioni del backup nativo (ignorate dalla versione HTML). */
data class NativeAccount(val name: String, val type: String, val opening: BigDecimal, val archived: Boolean)
data class NativeScenario(val name: String, val goal: LegacyGoal, val cuts: Map<String, Int>, val incomeDelta: BigDecimal, val removed: List<String>)
data class NativeDocument(val fileName: String, val mime: String, val size: Long, val createdAt: Long, val ocrText: String?)
/** Parte di un movimento diviso (il movimento è indicato dal suo identificativo stabile, la categoria dal nome). */
data class NativeSplit(val txId: String, val category: String?, val amount: BigDecimal, val position: Int, val auto: Boolean, val quota: String?)
data class NativeQuota(
    val name: String, val category: String, val amount: BigDecimal, val enabled: Boolean,
    val mode: String = "c", val tolerance: BigDecimal = BigDecimal.ZERO, val everyMonths: Int = 1, val dayFrom: Int = 0, val dayTo: Int = 0,
)
/** Entrata o spesa ricorrente (conto e categoria per nome). */
data class NativeRecurring(
    val name: String, val kind: String, val amount: BigDecimal, val account: String?, val category: String?, val everyMonths: Int, val day: Int,
    val start: java.time.LocalDate, val end: java.time.LocalDate?, val enabled: Boolean, val autoConfirm: Boolean, val last: java.time.LocalDate?,
)
/** Voce di scontrino ricordata e categoria (per nome). */
data class NativeItemMemory(val key: String, val category: String)
/** Obiettivo di risparmio (scadenza e quota possono mancare). */
data class NativeGoal(val name: String, val target: BigDecimal, val saved: BigDecimal, val deadline: java.time.LocalDate?, val monthly: BigDecimal?, val priority: Int, val enabled: Boolean)
/** Valore di un conto di investimento a una data (conto per nome). */
data class NativeValuation(val account: String, val date: java.time.LocalDate, val value: BigDecimal)
/** Correzione dell'utente: la categoria di entrata (per nome) è fissa o variabile. */
data class NativeIncomeNature(val category: String, val fixed: Boolean)
/** Sveglia o promemoria estratto conto (conto per nome). */
data class NativeReminder(
    val kind: String, val title: String, val note: String, val account: String?, val repeat: String, val dayOfWeek: Int, val dayOfMonth: Int,
    val date: java.time.LocalDate?, val hour: Int, val minute: Int, val enabled: Boolean,
)
/** Mese contabile scelto per un'entrata (movimento per identificativo, mese come AAAA-MM). */
data class NativeAccountingMonth(val txId: String, val month: String)
data class NativeIgnoreRule(val source: String, val phrase: String, val createdMs: Long, val enabled: Boolean)
data class NativeExtras(
    val accounts: List<NativeAccount> = emptyList(), val scenarios: List<NativeScenario> = emptyList(), val documents: List<NativeDocument> = emptyList(),
    val splits: List<NativeSplit> = emptyList(), val quotas: List<NativeQuota> = emptyList(),
    /** Identificativi dei movimenti già controllati (esclusi dalla sezione Verifica). */
    val reviewed: List<String> = emptyList(),
    val recurring: List<NativeRecurring> = emptyList(),
    val itemMemory: List<NativeItemMemory> = emptyList(),
    val ignoreRules: List<NativeIgnoreRule> = emptyList(),
    /** Esercenti che l'utente non vuole vedere come abbonamenti o rate. */
    val subIgnored: List<String> = emptyList(),
    val goals: List<NativeGoal> = emptyList(),
    val valuations: List<NativeValuation> = emptyList(),
    /** Coppie di movimenti che l'utente ha scartato come giroconti. */
    val transferIgnored: List<String> = emptyList(),
    val incomeNature: List<NativeIncomeNature> = emptyList(),
    val reminders: List<NativeReminder> = emptyList(),
    /** Conti gestiti da altri (per nome). */
    val managedAccounts: List<String> = emptyList(),
    /** Composizioni dei widget (testo JSON, vuoto = nessuna personalizzazione). */
    val widgetLayouts: String = "",
    /** Entrate contate in un mese contabile diverso da quello della data. */
    val accountingMonths: List<NativeAccountingMonth> = emptyList(),
)

data class BackupEnvelope(val savedAt: String, val device: String, val state: LegacyState, val extras: NativeExtras? = null)

class BackupFormatException(message: String) : Exception(message)

data class BackupIssue(val error: Boolean, val message: String)

object LegacyBackupCodec {
    const val APP = "gestione-spese"
    private val json = Json { prettyPrint = false }

    fun rsig(kw: String, sg: AmountSign, min: BigDecimal, max: BigDecimal) = "$kw|${sg.key}|${num(min)}|${num(max)}"
    fun rsig(r: LegacyRule) = rsig(r.kw, r.sg, r.min, r.max)
    /** Rappresentazione numerica canonica ("100", "0.5", "0"). */
    fun num(v: BigDecimal): String = if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.dec(): BigDecimal? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { NumberParser.parse(it) }
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    fun txId(acc: String, d: LocalDate, v: LocalDate, a: BigDecimal, t: String, n: Int) = "$acc|$d|$v|${Money.scale(a).toPlainString()}|$t#$n"

    fun parse(text: String): BackupEnvelope {
        val root = try { json.parseToJsonElement(text) } catch (e: Exception) { throw BackupFormatException("Il file non è un JSON valido.") }
        val ro = root.obj() ?: throw BackupFormatException("Formato non riconosciuto.")
        val data = ro["data"].obj()?.takeIf { it["tx"] != null } ?: ro
        val txArr = data["tx"].arr(); val catArr = data["cats"].arr(); val ruleArr = data["rules"].arr()
        if (txArr == null || catArr == null || ruleArr == null) throw BackupFormatException("Non è un backup di Gestione Spese (mancano movimenti, categorie o regole).")

        val seen = HashMap<String, Int>()
        val tx = txArr.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val d = DateParser.parseText(o["d"].str() ?: "") ?: return@mapNotNull null
            val v = DateParser.parseText(o["v"].str() ?: "") ?: d
            val a = o["a"].dec() ?: return@mapNotNull null
            val t = o["t"].str() ?: ""
            val acc = (o["acc"].str() ?: "").trim()          // vuoto = movimento senza conto
            val id = o["id"].str()?.takeIf { it.isNotBlank() } ?: run {
                val k = "$acc|$d|$v|${Money.scale(a).toPlainString()}|$t"
                val n = (seen[k] ?: 0) + 1; seen[k] = n; "$k#$n"
            }
            LegacyTx(id, d, v, Money.scale(a), t, acc, o["n"].str() ?: "", o["m"].str(), o["o"].str(), o["doc"].str())
        }
        val cats = catArr.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val n = o["n"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LegacyCat(n, TxKind.fromKey(o["k"].str()) ?: TxKind.SPESA, flag(o["f"]), o["c"].str() ?: "#888888", o["act"]?.let { flag(it) } ?: true, o["ic"].str()?.takeIf { it.isNotBlank() }, o["pc"].str()?.takeIf { it.isNotBlank() }, o["nt"].str()?.takeIf { SpendNature.fromKey(it) != null })
        }
        val rules = ruleArr.mapIndexedNotNull { i, e ->
            val o = e.obj() ?: return@mapIndexedNotNull null
            val cat = o["cat"].str() ?: return@mapIndexedNotNull null
            LegacyRule(o["id"].dec()?.toLong() ?: (i + 1L), o["kw"].str() ?: "", cat, AmountSign.fromKey(o["sg"].str()),
                (o["min"].dec() ?: BigDecimal.ZERO).abs(), (o["max"].dec() ?: BigDecimal.ZERO).abs())
        }
        val due = (data["due"].arr() ?: JsonArray(emptyList())).mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val name = o["name"].str() ?: return@mapNotNull null
            val next = DateParser.parseText(o["next"].str() ?: "") ?: return@mapNotNull null
            val amt = o["amt"].dec() ?: BigDecimal.ZERO
            val freq = DueFrequency.fromKey(o["freq"].str())
            LegacyDue(o["id"].str() ?: ("d" + Integer.toHexString("$name|$amt|$next|${freq.key}".hashCode())), name, Money.scale(amt), next, freq)
        }
        val ov = data["ov"].obj()?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap() ?: emptyMap()
        val budget = data["budget"].obj()?.mapNotNull { (k, v) -> v.dec()?.let { k to Money.scale(it) } }?.toMap() ?: emptyMap()
        val cuts = data["cuts"].obj()?.mapNotNull { (k, v) -> v.dec()?.let { k to it.toInt() } }?.toMap() ?: emptyMap()
        val mt = data["mt"].obj()?.mapNotNull { (k, v) -> v.dec()?.let { k to it.toLong() } }?.toMap() ?: emptyMap()
        val goal = parseGoal(data["goal"].obj())
        val extras = ro["native"].obj()?.let { parseExtras(it) }
        return BackupEnvelope(ro["savedAt"].str() ?: "", ro["device"].str() ?: "sconosciuto", LegacyState(tx, cats, rules, due, ov, budget, goal, cuts, mt), extras)
    }

    private fun flag(e: JsonElement?): Boolean = when (val s = e.str()) { null -> false; "true", "1" -> true; "false", "0", "" -> false; else -> s.toDoubleOrNull()?.let { it != 0.0 } ?: true }

    private fun parseGoal(o: JsonObject?): LegacyGoal = LegacyGoal(
        GoalMode.fromKey(o?.get("mode").str()),
        o?.get("val").dec() ?: BigDecimal(300),
        o?.get("months").dec()?.toInt() ?: 12,
        o?.get("income").dec(),
    )

    private fun parseExtras(o: JsonObject): NativeExtras = NativeExtras(
        accounts = o["accounts"].arr()?.mapNotNull { e -> e.obj()?.let { a ->
            NativeAccount(a["name"].str() ?: return@let null, a["type"].str() ?: "CONTO", a["opening"].dec() ?: BigDecimal.ZERO, flag(a["archived"])) } } ?: emptyList(),
        scenarios = o["scenarios"].arr()?.mapNotNull { e -> e.obj()?.let { s ->
            NativeScenario(s["name"].str() ?: return@let null, parseGoal(s["goal"].obj()),
                s["cuts"].obj()?.mapNotNull { (k, v) -> v.dec()?.let { k to it.toInt() } }?.toMap() ?: emptyMap(),
                s["incomeDelta"].dec() ?: BigDecimal.ZERO, s["removed"].arr()?.mapNotNull { it.str() } ?: emptyList()) } } ?: emptyList(),
        documents = o["documents"].arr()?.mapNotNull { e -> e.obj()?.let { d ->
            NativeDocument(d["file"].str() ?: return@let null, d["mime"].str() ?: "image/jpeg", d["size"].dec()?.toLong() ?: 0, d["created"].dec()?.toLong() ?: 0, d["ocr"].str()) } } ?: emptyList(),
        splits = o["splits"].arr()?.mapNotNull { e -> e.obj()?.let { s ->
            NativeSplit(s["tx"].str() ?: return@let null, s["cat"].str()?.takeIf { it.isNotBlank() }, s["amt"].dec() ?: return@let null, s["pos"].dec()?.toInt() ?: 0, flag(s["auto"]), s["quota"].str()?.takeIf { it.isNotBlank() }) } } ?: emptyList(),
        quotas = o["quotas"].arr()?.mapNotNull { e -> e.obj()?.let { q ->
            NativeQuota(q["name"].str() ?: return@let null, q["cat"].str() ?: return@let null, q["amt"].dec() ?: return@let null, q["on"]?.let { flag(it) } ?: true,
                q["mode"].str() ?: "c", q["tol"].dec() ?: BigDecimal.ZERO, q["every"].dec()?.toInt() ?: 1, q["from"].dec()?.toInt() ?: 0, q["to"].dec()?.toInt() ?: 0) } } ?: emptyList(),
        reviewed = o["reviewed"].arr()?.mapNotNull { it.str() } ?: emptyList(),
        accountingMonths = o["acctMonths"].arr()?.mapNotNull { e -> e.obj()?.let { m -> NativeAccountingMonth(m["tx"].str() ?: return@let null, m["m"].str()?.takeIf { v -> runCatching { java.time.YearMonth.parse(v) }.isSuccess } ?: return@let null) } } ?: emptyList(),
        recurring = o["recurring"].arr()?.mapNotNull { e -> e.obj()?.let { r ->
            NativeRecurring(r["name"].str() ?: return@let null, r["kind"].str() ?: "entrata", r["amt"].dec() ?: return@let null, r["acc"].str()?.takeIf { it.isNotBlank() }, r["cat"].str()?.takeIf { it.isNotBlank() },
                r["every"].dec()?.toInt() ?: 1, r["day"].dec()?.toInt() ?: 1, Dates.parseIso(r["start"].str()) ?: return@let null, Dates.parseIso(r["end"].str()), r["on"]?.let { flag(it) } ?: true, flag(r["auto"]), Dates.parseIso(r["last"].str())) } } ?: emptyList(),
        itemMemory = o["items"].arr()?.mapNotNull { e -> e.obj()?.let { m -> NativeItemMemory(m["k"].str()?.takeIf { it.isNotBlank() } ?: return@let null, m["cat"].str()?.takeIf { it.isNotBlank() } ?: return@let null) } } ?: emptyList(),
        ignoreRules = o["ignore"].arr()?.mapNotNull { e -> e.obj()?.let { r -> NativeIgnoreRule(r["src"].str() ?: "", r["p"].str()?.takeIf { it.isNotBlank() } ?: return@let null, r["t"].dec()?.toLong() ?: 0L, r["on"]?.let { flag(it) } ?: true) } } ?: emptyList(),
        subIgnored = o["subIgnored"].arr()?.mapNotNull { it.str() } ?: emptyList(),
        goals = o["goals"].arr()?.mapNotNull { e -> e.obj()?.let { g ->
            NativeGoal(g["n"].str()?.takeIf { it.isNotBlank() } ?: return@let null, g["t"].dec() ?: return@let null, g["s"].dec() ?: BigDecimal.ZERO,
                g["d"].dec()?.toLong()?.let { java.time.LocalDate.ofEpochDay(it) }, g["m"].dec(), g["p"].dec()?.toInt() ?: 0, g["on"]?.let { flag(it) } ?: true)
        } } ?: emptyList(),
        incomeNature = o["incNat"].arr()?.mapNotNull { e -> e.obj()?.let { r -> NativeIncomeNature(r["c"].str()?.takeIf { it.isNotBlank() } ?: return@let null, r["f"]?.let { flag(it) } ?: return@let null) } } ?: emptyList(),
        widgetLayouts = o["wl"].str().orEmpty(),
        managedAccounts = o["managed"].arr()?.mapNotNull { it.str()?.takeIf { n -> n.isNotBlank() } } ?: emptyList(),
        reminders = o["rem"].arr()?.mapNotNull { e -> e.obj()?.let { r ->
            NativeReminder(r["k"].str() ?: "CUSTOM", r["t"].str() ?: "", r["n"].str() ?: "", r["a"].str()?.takeIf { it.isNotBlank() }, r["r"].str() ?: "MONTHLY",
                r["w"].dec()?.toInt() ?: 1, r["m"].dec()?.toInt() ?: 1, r["d"].dec()?.toLong()?.let { java.time.LocalDate.ofEpochDay(it) }, r["h"].dec()?.toInt() ?: 9, r["mi"].dec()?.toInt() ?: 0, r["on"]?.let { flag(it) } ?: true)
        } } ?: emptyList(),
        transferIgnored = o["trIgnored"].arr()?.mapNotNull { it.str() } ?: emptyList(),
        valuations = o["vals"].arr()?.mapNotNull { e -> e.obj()?.let { v ->
            NativeValuation(v["a"].str()?.takeIf { it.isNotBlank() } ?: return@let null, v["d"].dec()?.toLong()?.let { java.time.LocalDate.ofEpochDay(it) } ?: return@let null, v["v"].dec() ?: return@let null)
        } } ?: emptyList(),
    )

    /** Verifica di coerenza dei dati prima del ripristino. */
    fun validate(env: BackupEnvelope): List<BackupIssue> {
        val s = env.state; val out = ArrayList<BackupIssue>()
        val catNames = s.cats.map { it.n }.toSet()
        if (s.cats.size != catNames.size) out.add(BackupIssue(false, "Ci sono categorie con lo stesso nome: verranno unite."))
        val ids = s.tx.groupingBy { it.id }.eachCount().filter { it.value > 1 }
        if (ids.isNotEmpty()) out.add(BackupIssue(false, "${ids.size} identificativi di movimento duplicati nel file: verrà importato un solo movimento per identificativo."))
        val missingRules = s.rules.count { it.cat !in catNames }
        if (missingRules > 0) out.add(BackupIssue(false, "$missingRules regole puntano a categorie inesistenti: verranno ignorate."))
        val missingOv = s.ov.count { it.value !in catNames }
        if (missingOv > 0) out.add(BackupIssue(false, "$missingOv assegnazioni manuali puntano a categorie inesistenti: verranno ignorate."))
        val badRe = s.rules.count { !it_isValid(it.kw) }
        if (badRe > 0) out.add(BackupIssue(false, "$badRe regole hanno un'espressione regolare non valida: non produrranno corrispondenze."))
        if (s.tx.isEmpty()) out.add(BackupIssue(false, "Il backup non contiene movimenti."))
        val future = s.tx.count { it.d.isAfter(LocalDate.now().plusYears(1)) || it.d.isBefore(LocalDate.of(1990, 1, 1)) }
        if (future > 0) out.add(BackupIssue(false, "$future movimenti hanno date anomale (prima del 1990 o oltre un anno nel futuro)."))
        return out
    }

    private fun it_isValid(kw: String): Boolean = it.spese.domain.classification.RuleEngine.isValidKeyword(kw)

    @OptIn(ExperimentalSerializationApi::class)
    private fun jnum(v: BigDecimal): JsonElement = JsonUnquotedLiteral(num(v))

    fun encode(env: BackupEnvelope): String {
        val s = env.state
        val root = buildJsonObject {
            put("app", APP); put("v", 2); put("savedAt", env.savedAt); put("device", env.device)
            put("data", buildJsonObject {
                put("tx", buildJsonArray { s.tx.forEach { t -> add(buildJsonObject {
                    put("id", t.id); put("d", t.d.toString()); put("v", t.v.toString())
                    put("a", JsonUnquotedLiteral(Money.scale(t.a).toPlainString())); put("t", t.t); put("acc", t.acc)
                    if (t.notes.isNotEmpty()) put("n", t.notes)
                    t.merchant?.let { put("m", it) }; t.origin?.let { put("o", it) }; t.doc?.let { put("doc", it) }
                }) } })
                put("cats", buildJsonArray { s.cats.forEach { c -> add(buildJsonObject {
                    put("n", c.n); put("k", c.k.key); put("f", if (c.f) 1 else 0); put("c", c.c); if (!c.active) put("act", 0); c.icon?.let { put("ic", it) }; c.parent?.let { put("pc", it) }; c.nature?.let { put("nt", it) }
                }) } })
                put("rules", buildJsonArray { s.rules.forEach { r -> add(buildJsonObject {
                    put("id", r.id); put("kw", r.kw); put("cat", r.cat); put("sg", r.sg.key); put("min", jnum(r.min)); put("max", jnum(r.max))
                }) } })
                put("due", buildJsonArray { s.due.forEach { d -> add(buildJsonObject {
                    put("id", d.id); put("name", d.name); put("amt", jnum(d.amt)); put("next", d.next.toString()); put("freq", d.freq.key)
                }) } })
                put("ov", buildJsonObject { s.ov.forEach { (k, v) -> put(k, v) } })
                put("budget", buildJsonObject { s.budget.forEach { (k, v) -> put(k, jnum(v)) } })
                put("goal", goalJson(s.goal))
                put("cuts", buildJsonObject { s.cuts.forEach { (k, v) -> put(k, v) } })
                put("mt", buildJsonObject { s.mt.forEach { (k, v) -> put(k, v) } })
            })
            env.extras?.let { x -> put("native", buildJsonObject {
                put("v", 1)
                put("accounts", buildJsonArray { x.accounts.forEach { a -> add(buildJsonObject {
                    put("name", a.name); put("type", a.type); put("opening", jnum(a.opening)); put("archived", if (a.archived) 1 else 0) }) } })
                put("scenarios", buildJsonArray { x.scenarios.forEach { sc -> add(buildJsonObject {
                    put("name", sc.name); put("goal", goalJson(sc.goal)); put("cuts", buildJsonObject { sc.cuts.forEach { (k, v) -> put(k, v) } })
                    put("incomeDelta", jnum(sc.incomeDelta)); putJsonArray("removed") { sc.removed.forEach { add(JsonPrimitive(it)) } } }) } })
                put("documents", buildJsonArray { x.documents.forEach { d -> add(buildJsonObject {
                    put("file", d.fileName); put("mime", d.mime); put("size", d.size); put("created", d.createdAt); d.ocrText?.let { put("ocr", it) } }) } })
                if (x.splits.isNotEmpty()) put("splits", buildJsonArray { x.splits.forEach { s -> add(buildJsonObject {
                    put("tx", s.txId); s.category?.let { put("cat", it) }; put("amt", jnum(s.amount)); put("pos", s.position); put("auto", if (s.auto) 1 else 0); s.quota?.let { put("quota", it) } }) } })
                if (x.quotas.isNotEmpty()) put("quotas", buildJsonArray { x.quotas.forEach { q -> add(buildJsonObject {
                    put("name", q.name); put("cat", q.category); put("amt", jnum(q.amount)); put("on", if (q.enabled) 1 else 0)
                    put("mode", q.mode); put("tol", jnum(q.tolerance)); put("every", q.everyMonths); put("from", q.dayFrom); put("to", q.dayTo) }) } })
                if (x.accountingMonths.isNotEmpty()) put("acctMonths", buildJsonArray { x.accountingMonths.forEach { m -> add(buildJsonObject { put("tx", m.txId); put("m", m.month) }) } })
                if (x.reviewed.isNotEmpty()) putJsonArray("reviewed") { x.reviewed.forEach { add(JsonPrimitive(it)) } }
                if (x.recurring.isNotEmpty()) put("recurring", buildJsonArray { x.recurring.forEach { r -> add(buildJsonObject {
                    put("name", r.name); put("kind", r.kind); put("amt", jnum(r.amount)); r.account?.let { put("acc", it) }; r.category?.let { put("cat", it) }
                    put("every", r.everyMonths); put("day", r.day); put("start", r.start.toString()); r.end?.let { put("end", it.toString()) }; put("on", if (r.enabled) 1 else 0); put("auto", if (r.autoConfirm) 1 else 0); r.last?.let { put("last", it.toString()) } }) } })
                if (x.itemMemory.isNotEmpty()) put("items", buildJsonArray { x.itemMemory.forEach { m -> add(buildJsonObject { put("k", m.key); put("cat", m.category) }) } })
                if (x.ignoreRules.isNotEmpty()) put("ignore", buildJsonArray { x.ignoreRules.forEach { r -> add(buildJsonObject { put("src", r.source); put("p", r.phrase); put("t", r.createdMs); put("on", if (r.enabled) 1 else 0) }) } })
                if (x.subIgnored.isNotEmpty()) putJsonArray("subIgnored") { x.subIgnored.forEach { add(JsonPrimitive(it)) } }
                if (x.goals.isNotEmpty()) putJsonArray("goals") { x.goals.forEach { g -> add(buildJsonObject {
                    put("n", g.name); put("t", jnum(g.target)); put("s", jnum(g.saved)); g.deadline?.let { put("d", it.toEpochDay()) }; g.monthly?.let { put("m", jnum(it)) }; put("p", g.priority); put("on", if (g.enabled) 1 else 0)
                }) } }
                if (x.incomeNature.isNotEmpty()) putJsonArray("incNat") { x.incomeNature.forEach { r -> add(buildJsonObject { put("c", r.category); put("f", if (r.fixed) 1 else 0) }) } }
                if (x.widgetLayouts.isNotBlank()) put("wl", x.widgetLayouts)
                if (x.managedAccounts.isNotEmpty()) putJsonArray("managed") { x.managedAccounts.forEach { add(JsonPrimitive(it)) } }
                if (x.reminders.isNotEmpty()) putJsonArray("rem") { x.reminders.forEach { r -> add(buildJsonObject {
                    put("k", r.kind); put("t", r.title); put("n", r.note); r.account?.let { put("a", it) }; put("r", r.repeat); put("w", r.dayOfWeek); put("m", r.dayOfMonth)
                    r.date?.let { put("d", it.toEpochDay()) }; put("h", r.hour); put("mi", r.minute); put("on", if (r.enabled) 1 else 0)
                }) } }
                if (x.transferIgnored.isNotEmpty()) putJsonArray("trIgnored") { x.transferIgnored.forEach { add(JsonPrimitive(it)) } }
                if (x.valuations.isNotEmpty()) putJsonArray("vals") { x.valuations.forEach { v -> add(buildJsonObject { put("a", v.account); put("d", v.date.toEpochDay()); put("v", jnum(v.value)) }) } }
            }) }
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    private fun goalJson(g: LegacyGoal): JsonObject = buildJsonObject {
        put("mode", g.mode.key); put("val", jnum(g.value)); put("months", g.months)
        if (g.income != null) put("income", jnum(g.income)) else put("income", "")
    }
}
