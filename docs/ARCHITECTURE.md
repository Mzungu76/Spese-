# Architettura

## Struttura dei moduli

```
app/                     Application, MainActivity, navigazione, worker, OCR (ML Kit) e le feature UI
  it.spese.app.*           shell: navigazione, scaffold comuni, filtri condivisi, notifiche/worker
  it.spese.feature.*       dashboard, transactions, categories, accounts, importer, simulation, analytics, ocr, alerts, settings
core/model/              (Kotlin JVM) modelli di dominio puri
core/common/             (Kotlin JVM) Money (BigDecimal), parsing numeri, date
domain/                  (Kotlin JVM) motori e casi d'uso, senza Android
  classification/          Merchant, RuleEngine/Classifier, duplicati, trasferimenti, dati predefiniti
  finance/                 filtri, statistiche, ricorrenze, avvisi, calcolo della Panoramica
  simulation/              piano, motore di simulazione, scenari, tendenze/previsioni
  importing/               CSV, lettore/scrittore XLSX, intestazioni, date, pianificazione import
  backup/                  codec e unione del formato HTML, mapper stato↔dominio
  export/  ocr/            CSV/XLSX, interpretazione testo di scontrini/fatture
  repository/  usecase/    contratti dei repository e casi d'uso
core/database/           (Android) Room: entità, DAO, migrazioni, schemi esportati
core/designsystem/       (Android) tema Material 3, componenti, grafici (Vico + Canvas)
data/                    (Android) repository Room, DataStore, archivio foto, backup/ripristino, DI Hilt
```

Dipendenze: `app → data → domain → core:model/common`, `data → core:database`, `app → core:designsystem`.
Il `domain` non conosce Android né Room: i motori finanziari, di classificazione e di simulazione sono testabili con JUnit puro
(`./gradlew :domain:test`, ~2 s) e **non esiste logica di business nei composable**: le schermate leggono uno stato dal ViewModel,
che lo costruisce con `DashboardCalculator`, `SimulationFactory`, `AlertEngine`… (stessa logica usata dalle notifiche in background).

Flusso dati: `Room → repository (Flow) → AppDataSource (una sola sottoscrizione, StateFlow<AppData>) → ViewModel (combine con i filtri, calcolo su Dispatchers.Default) → Compose`.

## Decisioni architetturali

| Decisione | Motivo |
|---|---|
| **Importi in centesimi `Long` nel DB, `BigDecimal` in memoria** | nessun errore di arrotondamento; `SUM` esatto; test `MoneyTest` |
| **Doppio solo al confine con la libreria grafica** | Vico/Canvas disegnano in `Double`; non entra mai in un calcolo monetario |
| **Un solo percorso per backup/unione/annullamento/sync: `LegacyState`** | stesso codice per file JSON, ZIP, snapshot di annullamento e backup automatici; compatibilità con l'HTML per costruzione |
| **Categorie per id nel DB, per nome nel backup** | l'HTML identifica per nome; il mapper traduce, le rinomine non rompono i riferimenti |
| **Fallback di sistema (`systemKey`)** | ogni movimento ha sempre una categoria ("Altro"/"Altre entrate") |
| **Lettore XLSX proprio (ZIP+SAX)** invece di Apache POI | POI è pesante e fragile su Android (AWT, ~20 MB, metodi); il formato richiesto è semplice e coperto da test di round-trip |
| **XLS binario non supportato** | richiede un parser OLE2/BIFF completo; l'utente riceve un messaggio chiaro (salva come xlsx/csv) |
| **Grafici: Vico per barre/linee, Canvas proprio per ciambella e barre orizzontali** | Vico 2.x non ha il grafico a torta; entrambi interattivi |
| **Nessun permesso `INTERNET`** (rimosso anche dalle librerie con `tools:node="remove"`) | l'offline è una garanzia del sistema, non una promessa |
| **ML Kit con modello incluso nell'APK** | OCR offline senza Google Play Services né download |
| **Backup cloud Android disabilitati** (`allowBackup=false`, regole di estrazione) | i dati finanziari escono solo per azione esplicita dell'utente |
| **Feature come pacchetti dentro `:app`** (non un modulo Gradle ciascuna) | ogni modulo Android costa configurazione e tempo di build; i confini sono già imposti da `domain`/`data`/`designsystem` e dalla struttura per pacchetti. Se il progetto crescesse, ogni `feature/*` si estrae senza modificare le dipendenze verso il basso |
| **Pacchetto `feature.importer`** (non `import`) | `import` è parola riservata in Java e rompe il codice generato da Hilt |
| **Niente `fallbackToDestructiveMigration`** | una migrazione mancante deve fallire in modo visibile, non cancellare dati; `MigrationTest` lo verifica |
| **Messaggi degli avvisi in italiano nel dominio** | l'app è monolingua; i messaggi sono identici all'HTML e riusati nelle notifiche |

## OCR e futura estensione

`TextRecognizer` (dominio) è l'unico punto di contatto con ML Kit (`MlKitTextRecognizer`). `ReceiptParser` interpreta il testo (esercente, data, totale, IVA, voci)
con euristiche per scontrini e fatture italiane; l'utente rivede **sempre** i dati prima del salvataggio. Per estratti conto fotografati o documenti multipagina
basta aggiungere un parser per tabelle di testo e un `ScanDocumentUseCase` che concateni le pagine: il contratto `TextRecognizer.recognize(path)` e l'archivio documenti non cambiano.

## Sicurezza e privacy

- Database e foto nella memoria privata dell'app (`filesDir`), non leggibili da altre app; nessun dato inviato a server.
- I backup escono solo con azione esplicita (Salva/Condividi/Sincronizza); ogni ripristino o azzeramento crea prima uno snapshot per annullare.
- Nessuna credenziale nel repository: il keystore di firma vive solo nei GitHub Secrets (`docs/CI.md`).

## Limiti noti

- Le euristiche OCR sono basate su regole: funzionano su scontrini nitidi; i campi non riconosciuti restano vuoti e vanno compilati a mano.
- I test UI girano su JVM (Robolectric), non su un emulatore/dispositivo reale: la prima verifica su telefono (fotocamera, permessi, SAF) è manuale.
- Non è implementata la cifratura del database a riposo (affidata alla cifratura del dispositivo); si può aggiungere SQLCipher senza toccare il dominio.
