-- Condivisione tra l'app madre e l'app dell'altra persona. Il servizio non vede mai dati in chiaro: i blob sono cifrati sul telefono.
CREATE TABLE IF NOT EXISTS shares (
  id TEXT PRIMARY KEY,
  owner_hash TEXT NOT NULL,   -- SHA-256 del codice dell'app madre
  guest_hash TEXT NOT NULL,   -- SHA-256 del codice dell'altra persona
  out_blob TEXT,              -- ultimo riepilogo pubblicato dall'app madre (cifrato)
  out_updated INTEGER,
  created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS inbox (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  share_id TEXT NOT NULL,
  blob TEXT NOT NULL,         -- una spesa rilevata dall'altro telefono (cifrata)
  created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS inbox_share ON inbox (share_id, seq);
