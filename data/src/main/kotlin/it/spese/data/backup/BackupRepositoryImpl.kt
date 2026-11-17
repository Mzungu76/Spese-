package it.spese.data.backup

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import it.spese.core.common.Money
import it.spese.core.database.AppDatabase
import it.spese.core.database.entity.*
import it.spese.core.model.*
import it.spese.data.files.DocumentFiles
import it.spese.data.mapper.toDomain
import it.spese.domain.backup.*
import it.spese.domain.classification.Classifier
import it.spese.domain.classification.DefaultData
import it.spese.domain.classification.Duplicates
import it.spese.domain.repository.*
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backup, ripristino, unione, annullamento e azzeramento. Tutto passa dalla struttura [LegacyState]
 * (formato della versione HTML): un solo percorso per file JSON, ZIP, snapshot di annullamento e backup automatici.
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: DocumentFiles,
    private val seeder: DatabaseSeeder,
    private val settings: SettingsRepository,
) : BackupRepository {
    private val undoFile get() = File(context.filesDir, "undo/last.json").also { it.parentFile?.mkdirs() }
    private val backupDir get() = File(context.filesDir, "backups").also { it.mkdirs() }

    // ---------- lettura stato ----------
    private suspend fun loadSnapshot(): Triple<DomainSnapshot, Map<String, Long>, Map<Long, String>> = db.withTransaction {
        val accounts = db.accounts().getAll().map { it.toDomain() }
        val catE = db.categories().getAll(); val cats = catE.map { it.toDomain() }
        val ruleE = db.rules().getAll(); val rules = ruleE.map { it.toDomain() }
        val txE = db.transactions().getAll(); val txs = txE.map { it.toDomain() }
        val dueE = db.dues().getAll(); val dues = dueE.map { it.toDomain() }
        val scen = db.scenarios().getScenarios(); val base = scen.firstOrNull { it.isBase }
        val cuts = db.scenarios().getCuts().filter { it.scenarioId == base?.id }.associate { it.categoryId to it.percent }
        val goal = base?.let { SavingsGoal(GoalMode.fromKey(it.goalMode), Money.fromCents(it.goalValueCents), it.goalMonths, it.expectedIncomeCents?.let { c -> Money.fromCents(c) }) } ?: SavingsGoal()
        // timestamp di modifica per entità (chiavi del backup HTML)
        val mt = HashMap<String, Long>()
        catE.forEach { mt["cat:${it.name}"] = it.updatedAt; if (it.budgetCents != null) mt["bud:${it.name}"] = it.budgetUpdatedAt }
        val catName = catE.associate { it.id to it.name }
        ruleE.forEach { r ->
            val name = catName[r.categoryId] ?: return@forEach
            mt["rule:" + LegacyBackupCodec.rsig(r.keyword, AmountSign.fromKey(r.sign), Money.fromCents(r.minCents), Money.fromCents(r.maxCents))] = r.updatedAt
        }
        txE.filter { it.manualCategory }.forEach { mt["ov:${it.externalId}"] = it.manualUpdatedAt }
        dueE.forEach { mt["due:${it.legacyId}"] = it.updatedAt }
        mt["goal"] = base?.updatedAt ?: 0
        val docs = db.documents().getAll().associate { it.id to it.fileName }
        Triple(DomainSnapshot(accounts, cats, rules, txs, dues, goal, cuts), mt, docs)
    }

    private suspend fun currentLegacy(): LegacyState { val (s, mt, d) = loadSnapshot(); return LegacyMapper.fromDomain(s, mt, d) }

    private val itemMemory = it.spese.data.settings.ItemMemoryRepositoryImpl(context)
    private val captureConfig = it.spese.data.settings.CaptureConfigRepositoryImpl(context)
    private val subReview = it.spese.data.settings.SubscriptionReviewRepositoryImpl(context)
    private val transferReview = it.spese.data.settings.TransferReviewRepositoryImpl(context)
    private val goalsRepo = it.spese.data.settings.GoalRepositoryImpl(context)
    private val valuationsRepo = it.spese.data.settings.ValuationRepositoryImpl(context)

    private suspend fun currentEnvelope(): BackupEnvelope {
        val (s, mt, docs) = loadSnapshot()
        val legacy = LegacyMapper.fromDomain(s, mt, docs)
        val catName = s.categories.associate { it.id to it.name }
        val catIdName = catName
        val scenarioE = db.scenarios().getScenarios().filter { !it.isBase }
        val cuts = db.scenarios().getCuts().groupBy { it.scenarioId }; val removed = db.scenarios().getRemoved().groupBy { it.scenarioId }
        val extras = NativeExtras(
            subIgnored = subReview.getIgnored().toList(),
            transferIgnored = transferReview.getIgnored().toList(),
            valuations = valuationsRepo.getAll().mapNotNull { v -> s.accounts.firstOrNull { it.id == v.accountId }?.let { NativeValuation(it.name, v.date, v.value) } },
            goals = goalsRepo.getAll().map { NativeGoal(it.name, it.target, it.saved, it.deadline, it.monthly, it.priority, it.enabled) },
            ignoreRules = captureConfig.get().ignoreRules.map { NativeIgnoreRule(it.source, it.phrase, it.createdMs, it.enabled) },
            itemMemory = itemMemory.get().mapNotNull { (k, c) -> catIdName[c]?.let { NativeItemMemory(k, it) } },
            accounts = s.accounts.map { NativeAccount(it.name, it.type.name, it.openingBalance, it.archived) },
            scenarios = scenarioE.map { e ->
                NativeScenario(e.name, LegacyGoal(GoalMode.fromKey(e.goalMode), Money.fromCents(e.goalValueCents), e.goalMonths, e.expectedIncomeCents?.let { Money.fromCents(it) }),
                    cuts[e.id].orEmpty().mapNotNull { c -> catName[c.categoryId]?.let { it to c.percent } }.toMap(), Money.fromCents(e.incomeDeltaCents), removed[e.id].orEmpty().map { it.itemKey })
            },
            documents = db.documents().getAll().map { NativeDocument(it.fileName, it.mimeType, it.sizeBytes, it.createdAt, it.ocrText) },
            splits = run {
                val txExt = db.transactions().getAll().associate { it.id to it.externalId }
                val quotaName = db.quotas().getAll().associate { it.id to it.name }
                db.splits().getAll().mapNotNull { sp -> txExt[sp.txId]?.let { NativeSplit(it, sp.categoryId?.let { c -> catName[c] }, Money.fromCents(sp.amountCents), sp.position, sp.auto, sp.quotaId?.let { q -> quotaName[q] }) } }
            },
            quotas = db.quotas().getAll().mapNotNull { q -> catName[q.categoryId]?.let { NativeQuota(q.name, it, Money.fromCents(q.amountCents), q.enabled, q.mode, Money.fromCents(q.toleranceCents), q.everyMonths, q.dayFrom, q.dayTo) } },
            reviewed = db.transactions().getAll().filter { it.reviewed }.map { it.externalId },
            recurring = run {
                val accName = db.accounts().getAll().associate { it.id to it.name }
                db.recurring().getAll().map { r -> NativeRecurring(r.name, r.kind, Money.fromCents(r.amountCents), r.accountId?.let { accName[it] }, r.categoryId?.let { catName[it] }, r.everyMonths, r.dayOfMonth,
                    java.time.LocalDate.ofEpochDay(r.startDate), r.endDate?.let { java.time.LocalDate.ofEpochDay(it) }, r.enabled, r.autoConfirm, r.lastGenerated?.let { java.time.LocalDate.ofEpochDay(it) }) }
            },
        )
        return BackupEnvelope(Instant.now().toString(), Build.MODEL ?: "Android", legacy, extras)
    }

    override suspend fun buildBackupJson(): String = withContext(Dispatchers.IO) { LegacyBackupCodec.encode(currentEnvelope()) }

    override suspend fun writeFullBackup(out: OutputStream) = withContext(Dispatchers.IO) {
        val json = buildBackupJson()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("backup.json")); z.write(json.toByteArray(Charsets.UTF_8)); z.closeEntry()
            db.documents().getAll().forEach { d ->
                files.readFile(d.fileName)?.let { z.putNextEntry(ZipEntry("documents/${d.fileName}")); z.write(it); z.closeEntry() }
            }
        }
    }

    override suspend fun planMerge(env: BackupEnvelope): MergePlan = LegacyMerge.plan(currentLegacy(), env.state)

    // ---------- ripristino ----------
    override suspend fun restore(env: BackupEnvelope, mode: RestoreMode, extraFiles: Map<String, ByteArray>): RestoreResult = withContext(Dispatchers.IO) {
        // snapshot di sicurezza PRIMA di qualunque modifica
        undoFile.writeText(LegacyBackupCodec.encode(currentEnvelope()))
        settings.update { it.copy(lastUndo = "snapshot") }
        applyEnvelope(env, mode, extraFiles)
    }

    private suspend fun applyEnvelope(env: BackupEnvelope, mode: RestoreMode, extraFiles: Map<String, ByteArray>): RestoreResult {
        val local = if (mode == RestoreMode.Merge) currentLegacy() else null
        val plan = local?.let { LegacyMerge.plan(it, env.state) }
        val target = if (local != null && plan != null) LegacyMerge.apply(local, env.state, plan) else env.state
        var added = 0
        db.withTransaction {
            if (mode == RestoreMode.Replace) wipeInTransaction()
            added = writeState(target, env.extras, mode == RestoreMode.Replace, extraFiles)
        }
        env.extras?.let { x ->
            if (x.subIgnored.isNotEmpty() || mode == RestoreMode.Replace) subReview.setIgnored(if (mode == RestoreMode.Replace) x.subIgnored.toSet() else subReview.getIgnored() + x.subIgnored)
        }
        // giroconti scartati (DataStore, identificativi dei movimenti)
        env.extras?.let { x ->
            if (x.transferIgnored.isNotEmpty() || mode == RestoreMode.Replace) transferReview.setIgnored(if (mode == RestoreMode.Replace) x.transferIgnored.toSet() else transferReview.getIgnored() + x.transferIgnored)
        }
        // valori dei conti di investimento (DataStore, conti per nome): con Sostituisci si riparte dal backup, con Unisci si aggiornano le stesse date
        env.extras?.let { x ->
            if (x.valuations.isNotEmpty() || mode == RestoreMode.Replace) {
                val ids = db.accounts().getAll().associate { it.name.lowercase() to it.id }
                val incoming = x.valuations.mapNotNull { v -> ids[v.account.lowercase()]?.let { id -> it.spese.core.model.Valuation(id, v.date, v.value) } }
                val base = if (mode == RestoreMode.Replace) emptyList() else valuationsRepo.getAll().filter { b -> incoming.none { it.accountId == b.accountId && it.date == b.date } }
                valuationsRepo.setAll(base + incoming)
            }
        }
        // obiettivi di risparmio (DataStore): con Sostituisci si riparte dal backup, con Unisci si aggiungono quelli con un nome nuovo
        env.extras?.let { x ->
            if (x.goals.isNotEmpty() || mode == RestoreMode.Replace) {
                val incoming = x.goals.map { g -> it.spese.core.model.Goal(0, g.name, g.target, g.saved, g.deadline, g.monthly, g.priority, g.enabled) }
                val base = if (mode == RestoreMode.Replace) emptyList() else goalsRepo.getAll()
                goalsRepo.setAll(base + incoming.filter { n -> base.none { it.name.equals(n.name, ignoreCase = true) } })
            }
        }
        // notifiche da ignorare (DataStore): con Sostituisci si riparte dal backup, con Unisci si aggiungono quelle mancanti
        env.extras?.let { x ->
            if (x.ignoreRules.isNotEmpty() || mode == RestoreMode.Replace) captureConfig.update { c ->
                val incoming = x.ignoreRules.map { r -> it.spese.domain.capture.IgnoreRule(r.source, r.phrase, r.createdMs, r.enabled) }
                val base = if (mode == RestoreMode.Replace) emptyList() else c.ignoreRules
                c.copy(ignoreRules = base + incoming.filter { n -> base.none { it.source == n.source && it.phrase == n.phrase } })
            }
        }
        // memoria delle voci di scontrino (DataStore, fuori dal database): le categorie si ritrovano per nome
        env.extras?.itemMemory?.takeIf { it.isNotEmpty() }?.let { items ->
            val idByName = db.categories().getAll().associate { it.name to it.id }
            if (mode == RestoreMode.Replace) itemMemory.clear()
            itemMemory.learn(items.mapNotNull { m -> idByName[m.category]?.let { m.key to it } }.toMap())
        } ?: if (mode == RestoreMode.Replace) itemMemory.clear() else Unit
        return RestoreResult(plan?.summary() ?: listOf("Dati sostituiti dal backup (${env.state.tx.size} movimenti)"), added)
    }

    private suspend fun wipeInTransaction() {
        db.recurring().deleteAll(); db.transactions().deleteAll(); db.importBatches().deleteAll(); db.dues().deleteAll(); db.scenarios().deleteAll()
        db.rules().deleteAll(); db.categories().deleteAll(); db.documents().deleteAll(); db.accounts().deleteAll()
    }

    /** Scrive lo stato (per nome/chiave naturale) nel database. Restituisce i movimenti inseriti. */
    private suspend fun writeState(s: LegacyState, extras: NativeExtras?, replace: Boolean, extraFiles: Map<String, ByteArray>): Int {
        val cd = db.categories()
        // categorie
        var order = (cd.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        s.cats.distinctBy { it.n }.forEachIndexed { i, c ->
            val old = cd.findByName(c.n)
            val mt = s.mt["cat:${c.n}"] ?: 0
            if (old != null) cd.update(old.copy(kind = c.k.key, flexible = c.f, color = LegacyMapper.parseColor(c.c), active = c.active, updatedAt = mt, icon = c.icon ?: old.icon, nature = c.nature ?: old.nature))
            else cd.insert(CategoryEntity(name = c.n, kind = c.k.key, flexible = c.f, color = LegacyMapper.parseColor(c.c), active = c.active,
                sortOrder = if (replace) i else order++, updatedAt = mt, icon = c.icon, nature = c.nature))
        }
        // sottocategorie (il backup indica il padre per nome)
        s.cats.filter { it.parent != null }.forEach { c ->
            val child = cd.findByName(c.n); val par = cd.findByName(c.parent!!)
            if (child != null && par != null && child.id != par.id && par.parentId == null && par.kind == child.kind) cd.setParent(child.id, par.id)
        }
        suspend fun ensure(name: String, kind: TxKind, key: String) {
            val all = cd.getAll()
            val existing = all.firstOrNull { it.systemKey == key } ?: all.firstOrNull { it.name == name && it.kind == kind.key }
            if (existing == null) cd.insert(CategoryEntity(name = name, kind = kind.key, flexible = kind == TxKind.SPESA, color = 0xFF888888, sortOrder = all.size, systemKey = key))
            else if (existing.systemKey != key) cd.update(existing.copy(systemKey = key))
        }
        ensure(DefaultData.FALLBACK_EXPENSE_NAME, TxKind.SPESA, Category.FALLBACK_EXPENSE)
        ensure(DefaultData.FALLBACK_INCOME_NAME, TxKind.ENTRATA, Category.FALLBACK_INCOME)
        val catIdByName = cd.getAll().associate { it.name to it.id }
        // budget
        s.budget.forEach { (name, v) ->
            val c = cd.findByName(name) ?: return@forEach
            val cents = Money.toCents(v)
            if (c.budgetCents != cents) cd.setBudget(c.id, cents, s.mt["bud:$name"] ?: 0)
        }
        // regole
        val rd = db.rules()
        val existing = rd.getAll().toMutableList()
        s.rules.forEachIndexed { i, r ->
            val cid = catIdByName[r.cat] ?: return@forEachIndexed
            val sig = LegacyBackupCodec.rsig(r)
            val mt = s.mt["rule:$sig"] ?: 0
            val hit = existing.firstOrNull { LegacyBackupCodec.rsig(it.keyword, AmountSign.fromKey(it.sign), Money.fromCents(it.minCents), Money.fromCents(it.maxCents)) == sig }
            if (hit != null) { existing.remove(hit); rd.update(hit.copy(categoryId = cid, priority = i, updatedAt = mt)) }
            else rd.insert(RuleEntity(keyword = r.kw, categoryId = cid, sign = r.sg.key, minCents = Money.toCents(r.min), maxCents = Money.toCents(r.max), priority = i, updatedAt = mt))
        }
        // conti (con tipo e saldo iniziale dalle estensioni native)
        val ad = db.accounts()
        val nativeAcc = extras?.accounts.orEmpty().associateBy { it.name }
        val accIds = HashMap<String, Long>()
        for (name in (s.tx.map { it.acc } + nativeAcc.keys).distinct()) {
            val old = ad.findByName(name)
            val na = nativeAcc[name]
            accIds[name] = old?.id ?: ad.insert(AccountEntity(name = name, type = na?.type ?: AccountType.CONTO.name, openingCents = na?.let { Money.toCents(it.opening) } ?: 0, archived = na?.archived ?: false))
        }
        // documenti (da backup completo)
        val docIds = HashMap<String, Long>()
        if (extras != null) for (d in extras.documents) {
            val bytes = extraFiles["documents/${d.fileName}"] ?: files.readFile(d.fileName) ?: continue
            if (db.documents().getAll().none { it.fileName == d.fileName }) files.writeFile(d.fileName, bytes)
            val ex = db.documents().getAll().firstOrNull { it.fileName == d.fileName }
            docIds[d.fileName] = ex?.id ?: db.documents().insert(DocumentEntity(fileName = d.fileName, mimeType = d.mime, sizeBytes = d.size, createdAt = d.createdAt, ocrText = d.ocrText))
        }
        // movimenti nuovi
        val td = db.transactions()
        val now = System.currentTimeMillis()
        val have = HashSet<String>().also { set -> td.getAll().forEach { set.add(it.externalId) } }
        val toInsert = s.tx.filter { have.add(it.id) }.map { t ->
            val accId = accIds.getValue(t.acc)
            TransactionEntity(externalId = t.id, dedupKey = Duplicates.key(accId.toString(), t.d, t.v, t.a, t.t), accountId = accId,
                bookingDate = t.d.toEpochDay(), valueDate = t.v.toEpochDay(), description = t.t, amountCents = Money.toCents(t.a),
                kind = if (t.a.signum() >= 0) TxKind.ENTRATA.key else TxKind.SPESA.key, notes = t.notes, merchantOverride = t.merchant,
                origin = t.origin ?: TxOrigin.BACKUP.name, documentId = t.doc?.let { docIds[it] }, createdAt = now)
        }
        var added = 0
        toInsert.chunked(500).forEach { added += td.insertAll(it).count { id -> id != -1L } }
        // assegnazioni manuali
        s.ov.forEach { (extId, catName) ->
            val cid = catIdByName[catName] ?: return@forEach
            val t = td.findByExternalId(extId) ?: return@forEach
            val kind = cd.getAll().first { it.id == cid }.kind
            if (!t.manualCategory || t.categoryId != cid) td.updateClassification(t.id, cid, kind, true, null, s.mt["ov:$extId"] ?: 0)
        }
        // scadenze
        val dd = db.dues()
        s.due.forEach { d ->
            val mt = s.mt["due:${d.id}"] ?: 0
            val old = dd.findByLegacyId(d.id)
            val e = DueEntity(old?.id ?: 0, d.id, d.name, Money.toCents(d.amt), d.next.toEpochDay(), d.freq.key, mt)
            if (old != null) dd.update(e) else dd.insert(e)
        }
        // obiettivo e tagli (scenario base)
        val sd = db.scenarios()
        val base = sd.getScenarios().firstOrNull { it.isBase }
        val goalE = ScenarioEntity(base?.id ?: 0, base?.name ?: "Piano attuale", true, s.goal.mode.key, Money.toCents(s.goal.value), s.goal.months,
            s.goal.income?.let { Money.toCents(it) }, base?.incomeDeltaCents ?: 0, s.mt["goal"] ?: 0)
        val baseId = if (base != null) { sd.update(goalE); base.id } else sd.insert(goalE)
        sd.clearCuts(baseId)
        sd.insertCuts(s.cuts.mapNotNull { (k, v) -> catIdByName[k]?.let { ScenarioCutEntity(baseId, it, v) } })
        // altri scenari salvati (solo estensione nativa)
        if (extras != null) {
            val existingNames = sd.getScenarios().map { it.name }.toSet()
            extras.scenarios.filter { it.name !in existingNames }.forEach { sc ->
                val id = sd.insert(ScenarioEntity(name = sc.name, isBase = false, goalMode = sc.goal.mode.key, goalValueCents = Money.toCents(sc.goal.value),
                    goalMonths = sc.goal.months, expectedIncomeCents = sc.goal.income?.let { Money.toCents(it) }, incomeDeltaCents = Money.toCents(sc.incomeDelta), updatedAt = now))
                sd.insertCuts(sc.cuts.mapNotNull { (k, v) -> catIdByName[k]?.let { ScenarioCutEntity(id, it, v) } })
                sd.insertRemoved(sc.removed.map { ScenarioRemovedEntity(id, it) })
            }
        }
        // classificazione dei nuovi movimenti
        val classifier = Classifier(cd.getAll().map { it.toDomain() }, rd.getAll().map { it.toDomain() })
        td.getAll().filter { !it.manualCategory }.forEach { t ->
            val c = classifier.classify(t.description, Money.fromCents(t.amountCents), null)
            if (c.categoryId != t.categoryId || c.kind.key != t.kind || c.ruleId != t.ruleId) td.updateClassification(t.id, c.categoryId, c.kind.key, false, c.ruleId, 0)
        }
        // estensioni native: quote in contanti, movimenti divisi e movimenti già controllati
        if (extras != null) {
            val quotaIds = HashMap<String, Long>()
            extras.quotas.forEach { q ->
                val cid = cd.findByName(q.category)?.id ?: return@forEach
                val old = db.quotas().getAll().firstOrNull { it.name == q.name }
                quotaIds[q.name] = old?.id ?: db.quotas().insert(CashQuotaEntity(name = q.name, categoryId = cid, amountCents = Money.toCents(q.amount), enabled = q.enabled, mode = q.mode, toleranceCents = Money.toCents(q.tolerance), everyMonths = q.everyMonths, dayFrom = q.dayFrom, dayTo = q.dayTo))
            }
            db.quotas().getAll().forEach { quotaIds.putIfAbsent(it.name, it.id) }
            val haveSplits = db.splits().getAll().map { it.txId }.toHashSet()
            extras.splits.groupBy { it.txId }.forEach { (ext, parts) ->
                val t = td.findByExternalId(ext) ?: return@forEach
                if (t.id in haveSplits) return@forEach
                db.splits().insertAll(parts.sortedBy { it.position }.mapIndexed { i, p -> SplitEntity(0, t.id, p.category?.let { cd.findByName(it)?.id }, Money.toCents(p.amount), i, p.auto, p.quota?.let { quotaIds[it] }) })
            }
            val haveRec = db.recurring().getAll().map { it.name to it.amountCents }.toHashSet()
            extras.recurring.forEach { r ->
                if ((r.name to Money.toCents(r.amount)) in haveRec) return@forEach
                db.recurring().insert(RecurringEntity(name = r.name, kind = r.kind, amountCents = Money.toCents(r.amount), accountId = r.account?.let { ad.findByName(it)?.id },
                    categoryId = r.category?.let { cd.findByName(it)?.id }, everyMonths = r.everyMonths, dayOfMonth = r.day, startDate = r.start.toEpochDay(), endDate = r.end?.toEpochDay(),
                    enabled = r.enabled, autoConfirm = r.autoConfirm, lastGenerated = r.last?.toEpochDay()))
            }
            val reviewedIds = extras.reviewed.mapNotNull { td.findByExternalId(it)?.id }
            if (reviewedIds.isNotEmpty()) reviewedIds.chunked(500).forEach { td.setReviewed(it, true) }
        }
        return added
    }

    // ---------- annullamento, azzeramento, backup automatici ----------
    override suspend fun hasUndo() = undoFile.exists()

    override suspend fun undoLast(): Boolean = withContext(Dispatchers.IO) {
        val f = undoFile
        if (!f.exists()) return@withContext false
        val env = LegacyBackupCodec.parse(f.readText())
        applyEnvelope(env, RestoreMode.Replace, emptyMap())
        f.delete()
        true
    }

    override suspend fun resetAll() = withContext(Dispatchers.IO) {
        undoFile.writeText(LegacyBackupCodec.encode(currentEnvelope()))
        settings.update { it.copy(lastUndo = "snapshot") }
        seeder.wipeAndSeed(); itemMemory.clear(); goalsRepo.setAll(emptyList()); valuationsRepo.setAll(emptyList())
    }

    override suspend fun autoBackup(keep: Int): String = withContext(Dispatchers.IO) {
        val name = "auto-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(Instant.now().atZone(ZoneId.systemDefault())) + ".json"
        val f = File(backupDir, name)
        f.writeText(buildBackupJson())
        backupDir.listFiles { x -> x.name.startsWith("auto-") }?.sortedByDescending { it.name }?.drop(maxOf(1, keep))?.forEach { it.delete() }
        f.absolutePath
    }

    override fun listAutoBackups(): List<File> = backupDir.listFiles { x -> x.name.startsWith("auto-") }?.sortedByDescending { it.name } ?: emptyList()
}
