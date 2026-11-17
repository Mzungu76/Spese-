# Gestione Spese

Applicazione Android **nativa** (Kotlin, Jetpack Compose, Material 3) per tenere sotto controllo entrate e spese, migrazione dell'app web "Gestione Spese" (`legacy/gestione-spese.html`).
Funziona **interamente offline**: nessun account, nessun cloud, nessun permesso di rete. I dati stanno in un database locale (Room).

## Cosa fa

- **Assistente quotidiano** – in Home, "oggi puoi spendere X": entrate attese − obiettivo di risparmio − spese annuali spalmate − uscite fisse = budget per le spese variabili, con avanzamento, stato (in linea / vai piano / esaurito) e calcolo spiegato. Consigli e riconoscimenti sui tuoi dati, avviso quando i dati sono fermi.
- **Il tuo mese** – riepilogo del mese chiuso, andamento del risparmio, fondo di emergenza (mesi di spese essenziali coperti) con destinazione di ciò che avanza, spese annuali da accantonare, lezione del giorno.
- **Configurazione guidata** – propone entrate, spese fisse, obiettivo e budget dai movimenti; nulla è salvato finché non confermi.
- **Spese dalle notifiche (opzionale)** – i pagamenti notificati da banca/wallet scelti dall'utente diventano movimenti, sostituiti automaticamente dall'estratto conto quando lo importi. Tutto sul telefono.
- **Notifiche** – avvisi, "oggi puoi spendere" ogni giorno e riepilogo della settimana il lunedì; scorciatoia "Nuova spesa" sull'icona.
- **Panoramica** – entrate, spese, saldo, risparmio, media mensile, giroconti esclusi, grafici interattivi (ciambella, barre, impilate, cumulato, esercenti), avvisi.
- **Movimenti** – inserimento, modifica, duplicazione, ricerca, filtri, selezione multipla, ordinamento, esportazione CSV/Excel.
- **Categorie** – classifica per peso con icone, variazioni sul periodo precedente, dettaglio per categoria (andamento, esercenti, sottocategorie), natura della spesa (fissa/necessaria/discrezionale), budget suggerito, "dove puoi risparmiare" e simulazione "e se la riducessi?" collegata al Piano.
- **Gestione** – regole testuali/regex con segno e soglie, priorità, assegnazione manuale che prevale, movimenti "Da fare", conti multipli.
- **Piano e simulazioni** – obiettivi, tagli per categoria, "e se…", scadenze future, scenari salvati e confrontabili, proiezioni a 6/12 mesi.
- **Avvisi e ricorrenti** – budget, scadenze, ricorrenze rilevate, spese non previste, notifiche locali configurabili.
- **Importazione** – xlsx/csv/txt con anteprima e correzione delle colonne; backup JSON della versione web (unisci o sostituisci).
- **Scansione OCR** – scontrini e fatture (fotocamera o galleria), riconoscimento sul dispositivo, revisione obbligatoria, foto associata al movimento.
- **Backup** – JSON compatibile con la versione web, ZIP con foto, backup automatici, sincronizzazione con un file condiviso, annulla ultima importazione.

## Per iniziare

1. Scarica l'APK dagli artifact di *Actions* (`gestione-spese-debug-apk`) e installalo (abilita "Origini sconosciute").
2. Importa un estratto conto dal menu ⋮ → *Importa estratto conto*, oppure un backup della versione web da *Backup e impostazioni*.

## Sviluppo

```bash
./gradlew :domain:test            # motori (JVM puro)
./gradlew testDebugUnitTest       # database, backup, UI (Robolectric)
./gradlew :app:assembleDebug      # APK debug
```

Documentazione: [`MIGRATION_PLAN.md`](MIGRATION_PLAN.md) (analisi e corrispondenza con l'HTML) · [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) · [`docs/CI.md`](docs/CI.md) (pipeline e firma).
