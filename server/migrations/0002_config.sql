-- Impostazioni del servizio. «create_key_hash» = SHA-256 (esadecimale) della chiave di creazione: alternativa al segreto CREATE_KEY di Cloudflare.
CREATE TABLE IF NOT EXISTS config (
  name TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
