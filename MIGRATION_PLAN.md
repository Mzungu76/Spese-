# MIGRATION_PLAN – Gestione Spese: da HTML/JS ad Android nativo

Documento di analisi e di piano. Il riferimento funzionale è `legacy/gestione-spese.html` (file singolo, ~1.150 righe di logica + SheetJS + Chart.js).
Ogni affermazione sulla versione HTML è stata ricavata leggendo il codice; la corrispondenza della nuova app è verificata da test
(vedi §9), compreso un **golden master** generato eseguendo davvero la pagina HTML in Chromium (`legacy/golden-master/`).

## 1. Cos'è la versione HTML

- Persistenza: `localStorage` (`spese_v1`), un unico oggetto `S`; snapshot di annullamento in `spese_undo`; file di sincronizzazione via File System Access API (solo Chrome/Edge desktop).
- Interfaccia: 5 sezioni (Panoramica, Movimenti, Categorie e regole, Piano e simulazioni, Avvisi e ricorrenti); layout desktop e layout mobile separati (stessa logica).
- Librerie: SheetJS (lettura xlsx/csv), Chart.js (grafici).
- Dati: importi come `number` (double) arrotondati a 2 decimali; categorie e conti identificati **per nome**.

## 2. Funzionalità esistenti → moduli Android

| # | Funzionalità HTML | Dove nell'app Android | Stato |
|---|---|---|---|
| 1 | Importazione estratti conto xlsx/xls/csv/txt, più file, nome conto richiesto | `domain/importing` + `feature/importer` (anteprima, mappatura colonne, scelta conto) | ✅ xlsx/csv/txt. **xls binario: non supportato** (messaggio che guida alla conversione) |
| 2 | Riconoscimento intestazioni (`importo` + `descrizione/causale/operazioni`, date contabile/valuta) | `HeaderDetector` (stessi criteri) + `TableAnalyzer`: riconoscimento **dal contenuto** (date, importi con segno / Dare-Avere / colonna D-A, testi, extra descrizione), file senza intestazione, formati data e decimali, formati banca ricordati; wizard a 3 passi | ✅ |
| 3 | Deduplica con insieme a molteplicità; id `conto\|data\|valuta\|importo\|testo#n` | `Duplicates.plan`, `ImportPlanner`, `externalId` identico all'HTML | ✅ verificato su dati reali |
| 4 | Classificazione: regole testuali, `re:` regex, segno, importo min/max, prima regola vince | `RuleEngine`, `Classifier` | ✅ identica (223/223 movimenti del golden master) |
| 5 | Assegnazione manuale (`ov`) che prevale sulle regole | `Transaction.manualCategory` | ✅ |
| 6 | Categorie: nome, tipo (spesa/entrata/trasferimento), colore, "comprimibile", ordine, rinomina, eliminazione (→ "Altro") | `feature/categories`, `SaveCategoryUseCase`, `DeleteCategoryUseCase` | ✅ + budget e stato attivo |
| 7 | Regole: CRUD, riordino, hit count, "crea regola da movimento", "da classificare" per esercente | `feature/categories`, `CreateRuleUseCase` | ✅ |
| 8 | Conti: derivati dai movimenti, rinomina = unione | `feature/accounts`, `AccountRepository.renameOrMerge` | ✅ + tipo, saldo iniziale, archivio |
| 9 | Filtri: periodo (Tutto/Anno/3/6/mese corrente/mese), dal-al, conto, categoria, tipo, testo, importo min/max | `domain/finance/Filters.kt`, `FilterStore`, `FilterHeader` | ✅ risoluzione periodi identica |
| 10 | Panoramica: KPI, per conto, torta, barre entrate/spese + saldo, impilato, cumulato, esercenti, dettaglio categorie, top 10 spese, avvisi | `DashboardCalculator`, `feature/dashboard` | ✅ |
| 11 | Movimenti: tabella/lista, ordinamento, paginazione, cambio categoria, "+regola", assegna a tutti i filtrati, export CSV | `feature/transactions` | ✅ + selezione multipla, modifica/duplica/elimina, inserimento manuale |
| 12 | Piano: obiettivo (mensile/totale+mesi), entrate attese, suggerimenti 10/15/20 %, 50/30/20, fondo emergenza, simulatore tagli, "distribuisci", proiezione 12 mesi, budget | `PlanEngine`, `SimulationEngine`, `feature/simulation` | ✅ ampliato (§7) |
| 13 | Avvisi: budget superato / 80 % / ritmo; risparmio sotto obiettivo; ricorrenza attesa; scadenze in ritardo/imminenti | `AlertEngine` | ✅ messaggi identici (verificato) + "spese non previste" |
| 14 | Ricorrenze rilevate (≥3 addebiti, 3 mesi, mediana 24–40 gg, CV ≤ 25 %) | `RecurringDetector` | ✅ identico |
| 15 | Scadenze manuali (nome, importo, data, ogni mese/anno/una volta), "Pagata", elimina | `DueRepository`, `PlanEngine.advanceDue`, `feature/alerts` | ✅ |
| 16 | Backup JSON `{app:'gestione-spese', v:2, data:{…}}`, importa con Unisci/Sostituisci, riepilogo differenze | `LegacyBackupCodec`, `LegacyMerge`, `feature/settings` | ✅ formato letto e scritto |
| 17 | Annulla importazione (snapshot) | `UndoImportUseCase` (lotto di file o snapshot di ripristino) | ✅ |
| 18 | Sincronizzazione con file in cartella condivisa | Storage Access Framework (`SettingsViewModel.link/syncNow`) | ✅ stessa semantica di unione |
| 19 | Condividi backup (Web Share) | share sheet Android + "Salva backup" (SAF) | ✅ |
| 20 | Azzera dati | `BackupRepository.resetAll` (con snapshot per annullare) | ✅ |
| 21 | Categorie/regole predefinite (21 + 31) | `DefaultData` (identiche, test di conteggio) | ✅ |

## 3. Modello dati

| HTML (`S`) | Room (v1) |
|---|---|
| `tx[] {id,d,v,a,t,acc}` (+ `cat,k,man,rid` calcolati) | `transactions` (importo in **centesimi `Long`**, date come epoch-day, `externalId` = id HTML, `dedupKey`, `kind`, `categoryId`, `manualCategory`, `ruleId`, `merchantOverride`, `notes`, `origin`, `documentId`, `importBatchId`) |
| conti = stringhe `acc` | `accounts` (nome univoco, tipo, saldo iniziale, archivio) |
| `cats[] {n,k,f,c}` + `budget{}` | `categories` (nome univoco, tipo, `flexible`, colore, `budgetCents`, `active`, `sortOrder`, `systemKey`) |
| `rules[] {id,kw,cat,sg,min,max}` | `rules` (FK categoria, `priority`) |
| `ov{txId→cat}` | colonna `manualCategory` della transazione |
| `due[] {id,name,amt,next,freq}` | `dues` |
| `goal`, `cuts` | `scenarios` (scenario base) + `scenario_cuts`; `scenario_removed` per le ricorrenze escluse |
| — | `documents` (foto, testo OCR), `import_batches` |
| `mt`/`sig` (timestamp per l'unione) | colonne `updatedAt`/`budgetUpdatedAt`/`manualUpdatedAt` |

Integrità: FK con `RESTRICT` sul conto, `SET NULL` su categoria/documento/lotto, `CASCADE` su regole e tagli; indici su data, categoria, conto+chiave, id esterno univoco. Schema esportato in `core/database/schemas/` e controllato dai test di migrazione.

## 4. Regole di calcolo (invarianti da preservare)

- **Importi**: `BigDecimal` a 2 decimali (HALF_UP); nel database interi in centesimi. Nessun `Float`/`Double` nei calcoli monetari (le librerie grafiche ricevono `Double` solo al momento del disegno).
- **Classificazione**: manuale → prima regola (priorità crescente) che soddisfa: |importo| ≥ min (se ≠0), ≤ max (se ≠0), segno, testo (contiene, minuscolo) o regex `re:` (case-insensitive) → altrimenti "Altre entrate" (importo ≥ 0) / "Altro". Il **tipo** del movimento deriva dalla categoria (trasferimento escluso da entrate/spese).
- **Statistiche**: entrate = Σ importi dei movimenti di tipo entrata; spese = −Σ importi di tipo spesa (un rimborso in categoria di spesa riduce la spesa); i trasferimenti sono sommati a parte (`tr`).
- **Mesi coperti**: `max(1, (ultimo − primo + 1 giorni) / 30,44)`; medie mensili = totale / mesi.
- **Periodi**: l'estremo finale è l'ultimo movimento (non "oggi"): Anno = dal 1/1; Mese corrente = dal 1° del mese dell'ultimo movimento; 3/6 mesi = dal 1° del mese di (ultimo − n+1), tagliati al primo movimento.
- **Piano**: risparmio = entrate attese (o media) − spese medie; obiettivo mensile = valore, oppure totale/mesi; "distribuisci" = `min(100, ceil(gap/flexAvg·100/5)·5)` % su tutte le voci comprimibili; budget proposto = `round(media·(1−taglio%)/5)·5`; suggerimenti = 10/15/20 % delle entrate arrotondati a 10; fondo emergenza = 3–6 × spese.
- **Avvisi**: «oggi» = data reale, ma se l'ultimo movimento è più vecchio di 45 giorni si usa l'ultimo movimento. Budget ≥100 % critico; ≥80 % attenzione; proiezione a fine mese >105 % (dal 7° giorno) attenzione; risparmio medio ultimi 3 mesi < obiettivo; ricorrenza attesa entro −3/+7 giorni; scadenze in ritardo (critico) o entro 7 giorni.
- **Ricorrenze**: stesso esercente, spesa, ≥3 addebiti in ≥3 mesi, mediana intervalli 24–40 gg, deviazione standard ≤25 % della media; escluse "Contanti" e "Commissioni e bolli". Prossima = ultimo + mediana.
- **Esercente**: estrazione per espressioni regolari (`Merchant.extract`, porta fedele di `merchant()`).
- **Unione di backup**: movimenti mancanti aggiunti per id; per categorie/regole/assegnazioni/budget/scadenze/obiettivo vince il timestamp `mt` più recente; le eliminazioni non si propagano; l'ordine delle regole segue il backup più recente.

## 5. Compatibilità con i backup HTML

1. `LegacyBackupCodec.parse` accetta `{app,v:2,savedAt,device,data}` e anche l'oggetto `data` nudo; tollera campi mancanti, numeri come stringhe (`goal.val` è una stringa nell'HTML), importi a virgola mobile (convertiti in `BigDecimal`).
2. **Verifica** prima del ripristino (`validate`): id duplicati, regole/assegnazioni su categorie inesistenti, regex non valide, date anomale, backup vuoto.
3. **Riconoscimento duplicati**: per `id` movimento (insieme con molteplicità).
4. **Unisci** (default) o **Sostituisci tutto** (con conferma); prima di entrambi viene salvato uno snapshot → "Annulla ultima importazione".
5. L'app **scrive** lo stesso formato (con estensioni `native` ignorate dall'HTML): un backup nativo si può riaprire nella versione web. Test: lettura → scrittura → rilettura senza perdite.

## 6. Importazione, esportazione, sincronizzazione

- Import: xlsx (lettore ZIP+SAX proprio, niente Apache POI: leggero e compatibile con Android), csv/txt (delimitatore automatico `; , \t |`, virgolette, BOM, UTF-8 con ripiego Windows-1252), numeri italiani/europei/anglosassoni, segni finali, parentesi, date `dd/mm/yyyy`, `dd-mm-yy`, `yyyy-mm-dd`, `yyyymmdd`, `5 mar 2024`, seriali Excel.
- Export: JSON (compatibile), ZIP completo (JSON + foto), CSV (estensione del formato HTML, reimportabile), XLSX (movimenti, categorie, riepilogo mensile).
- Sincronizzazione: file `.json` scelto con il selettore di Android (Drive/OneDrive/Dropbox), unione + riscrittura, senza server.

## 7. Funzionalità aggiuntive (non presenti nell'HTML)

Inserimento/modifica/duplicazione/eliminazione manuale, selezione multipla e azioni di massa, ricerca anche su esercente e note, note ed esercente modificabile, rilevamento dei giroconti tra conti e dei duplicati probabili (`TransferDetector`, `Duplicates.findProbable`), importazione con anteprima e correzione colonne, motore di simulazione (scenari salvati e confrontabili, "e se…": variazione entrate, eliminazione di una ricorrenza o scadenza, scadenze future nel saldo, tempo per raggiungere un obiettivo, saldo a 6/12 mesi), sezione Analisi con 10 tipi di grafico interattivi (zoom, tooltip, selezione categorie) e previsioni con regressione lineare, OCR di scontrini/fatture (CameraX + ML Kit on-device) con revisione obbligatoria, foto associate ai movimenti, notifiche locali (WorkManager), backup automatici con rotazione, backup ZIP con foto, tema chiaro/scuro, spese non previste.

## 8. Differenze intenzionali rispetto all'HTML (migliorie, tutte coperte da test)

- Righe di import con data/importo non validi sono **segnalate** (l'HTML le scartava in silenzio).
- Numeri con più punti (`1.234.567`) sono lette come migliaia; un solo punto resta decimale come nell'HTML.
- Date `mm/dd/yyyy` riconosciute quando il giorno > 12 nel secondo campo.
- L'id delle scadenze importate da vecchi backup senza id è deterministico (nell'HTML casuale → duplicazioni a ogni import).
- Le categorie "Altro"/"Altre entrate" sono di sistema (non eliminabili): garantiscono sempre un fallback (l'HTML ricreava solo "Altro").

## 9. Piano di migrazione e stato

| Fase | Contenuto | Stato |
|---|---|---|
| 1 Analisi | Questo documento, golden master dall'HTML | ✅ |
| 2 Architettura | Moduli Gradle, schema, dipendenze (`docs/ARCHITECTURE.md`) | ✅ |
| 3 Base Android | Progetto Kotlin, tema M3, navigazione, Room, Hilt | ✅ |
| 4 Migrazione funzionale | Tutte le funzioni della tabella §2 | ✅ |
| 5 Analisi e simulazioni | Motore, scenari, grafici | ✅ |
| 6 OCR | CameraX + ML Kit + revisione | ✅ (vedi limiti in `docs/ARCHITECTURE.md`) |
| 7 Test | 86+ test automatici | ✅ |
| 8 Automazione | GitHub Actions, APK debug/release | ✅ (verifica su GitHub: vedi PR) |

### Test automatici (cosa copre cosa)

| Area richiesta | Test |
|---|---|
| Calcoli monetari | `MoneyTest` (esattezza BigDecimal, formati, parsing) |
| Classificazione, regole, trasferimenti | `ClassificationTest` (+ golden 223 movimenti), `ImportTest` (duplicati/trasferimenti) |
| Import CSV / XLSX | `ImportTest` (delimitatori, charset, Dare/Avere, preambolo, XLS rifiutato, round-trip), `DatabaseTest` (import su DB reale) |
| Duplicati | `ImportTest`, `DatabaseTest` (reimport = 0 nuovi; id = HTML) |
| Budget, avvisi, ricorrenze | `FinanceGoldenTest` (messaggi identici all'HTML) |
| Simulazioni e previsioni | `SimulationTest` |
| Migrazioni DB | `MigrationTest` (schema esportato, FK, regola "una migrazione per versione") |
| Backup HTML | `BackupTest`, `DatabaseTest` (ripristino, unione, annullamento, round-trip) |
| UI | `MainFlowTest` (Hilt+Room+Navigation+Compose su JVM: avvio, 5 sezioni, nuovo movimento, backup HTML in Panoramica, tutti i grafici, Piano, Avvisi, Impostazioni, Conti) |
