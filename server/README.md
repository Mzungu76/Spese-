# Servizio di condivisione

Piccolo servizio (Cloudflare Worker + database D1) con cui l'app madre e l'app dell'altra persona si scambiano dati **cifrati sul telefono**: il servizio non vede mai importi o descrizioni, solo testo illeggibile.

## Cosa fa
- `POST /v1/shares` crea una condivisione e restituisce tre cose, una sola volta: `id`, `ownerToken` (per l'app madre) e `guestToken` (per l'altra persona). Serve la chiave di creazione (intestazione `X-Create-Key`).
- `PUT /v1/shares/{id}/out` (madre) pubblica il riepilogo; `GET` (madre o ospite) lo legge. Con `?since=<aggiornamento>` risponde `unchanged` se non è cambiato.
- `POST /v1/shares/{id}/in` (ospite) manda spese rilevate; `GET /v1/shares/{id}/in?after=<n>` (madre) le legge in ordine; `POST /v1/shares/{id}/in/ack` (madre) libera quelle già importate.
- `DELETE /v1/shares/{id}` (madre) revoca tutto.
- Limiti: 100.000 caratteri per voce, 50 voci per richiesta, 5.000 spese in attesa per condivisione.

## Provare in locale
```
cd server && node --test
```
(serve Node 22 o più recente: i test usano un SQLite vero al posto di D1).

## Pubblicare su Cloudflare (una volta)
1. **Database**: già creato nel tuo account (`spese-condivisione`, id in `wrangler.toml`).
2. **Tabelle**: già create (contenuto di `migrations/0001_init.sql`).
3. **Chiave di creazione**: scegli una frase lunga e casuale e salvala come segreto `CREATE_KEY` del Worker (Impostazioni › Variabili e segreti, oppure `npx wrangler secret put CREATE_KEY`). Serve solo all'app madre per creare condivisioni; non va condivisa.
4. **Pubblicazione**: da questa cartella `npx wrangler deploy`, oppure collega il repository a Cloudflare (Workers › Crea › Importa un repository) con cartella radice `server`.

L'indirizzo del Worker (`https://spese.<tuo-nome>.workers.dev`) e la chiave di creazione si inseriscono una volta nell'app madre.
