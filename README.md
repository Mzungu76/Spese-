# Gestione Spese

Applicazione Android **nativa** (Kotlin, Jetpack Compose, Material 3) per tenere sotto controllo entrate e spese, migrazione dell'app web "Gestione Spese" (`legacy/gestione-spese.html`).
Funziona **interamente offline**: nessun account, nessun cloud, nessun permesso di rete. I dati stanno in un database locale (Room).

## Cosa fa

- **Panoramica** – entrate, spese, saldo, risparmio, media mensile, giroconti esclusi, grafici interattivi (ciambella, barre, impilate, cumulato, esercenti), avvisi.
- **Movimenti** – inserimento, modifica, duplicazione, ricerca, filtri, selezione multipla, ordinamento, esportazione CSV/Excel.
- **Categorie** – classifica per peso con icone, variazioni sul periodo precedente, dettaglio per categoria (andamento, esercenti, sottocategorie), natura della spesa (fissa/necessaria/discrezionale), budget suggerito, "dove puoi risparmiare" e simulazione "e se la riducessi?" collegata al Piano.
- **Gestione** – regole testuali/regex con segno e soglie, priorità, assegnazione manuale che prevale, movimenti "Da fare", conti multipli.
- **Piano e simulazioni** – obiettivi, tagli per categoria, "e se…", scadenze future, scenari salvati e confrontabili, proiezioni a 6/12 mesi.
- **Avvisi e ricorrenti** – budget, scadenze, ricorrenze rilevate, spese non previste, notifiche locali configurabili.
- **Importazione** – xlsx/csv/txt con anteprima e correzione delle colonne; backup JSON della versione web (unisci o sostituisci).
- **Scansione OCR** – scontrini e fatture (fotocamera o galleria), riconoscimento sul dispositivo, revisione obbligatoria, foto associata al movimento.
- **Movimenti automatici** – (opzionale) legge le notifiche e gli SMS delle sole app scelte (Revolut, Google Wallet, Postepay, Hype…), crea movimenti provvisori classificati dalle regole e li sostituisce con le righe dell'estratto conto all'importazione.
- **Prelievi e quote in contanti** – un movimento si può dividere su più categorie (es. prelievo da 800 € = 500 € Affitto + 300 € Contanti); le quote fisse (affitto) si staccano da sole dal prelievo giusto: primo del periodo che le copre, ogni prelievo che le copre o importo esatto con tolleranza; ogni mese o ogni N mesi, entro una finestra di giorni, anche più quote nello stesso prelievo.
- **Confronto e tendenze** – in Home si scelgono una o più categorie e il grafico mostra il loro andamento accanto alle entrate, con l'incidenza sulle entrate; la schermata Tendenze confronta ogni categoria con lo storico e proietta a 1, 3 e 6 mesi con l'effetto sul bilancio.
- **Ricerca** – su tutto lo storico, senza accenti, più parole, anche per importo o data.
- **Verifica** (in Movimenti) – i movimenti la cui associazione non è certa: divisi in automatico, provvisori da notifica, regole con soglia di importo, importi insoliti.
- **Report PDF** (menu ⋮) – modelli pronti (riepilogo mensile e annuale, categorie, tendenze e proiezioni, budget, elenco movimenti, contanti, per il commercialista) e personalizzabili: periodo, conto, categorie, sezioni e ordine, grafici, orientamento; anteprima, salvataggio e condivisione; i tuoi modelli si salvano.
- **Entrate e spese ricorrenti** – stipendio, affitto incassato, abbonamenti: ogni mese (o ogni 2/3/6/12 mesi), nel giorno scelto, con categoria fissa o dalle regole; l'app crea il movimento da sola come provvisorio (in Verifica) e l'estratto conto lo sostituisce.
- **Mese contabile** (Impostazioni) – opzionale: il mese può iniziare in un giorno a scelta (es. il 20, dal 20 al 19 del mese dopo) invece che dal calendario; periodi, confronti, budget, tendenze, grafici e report lo seguono.
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
