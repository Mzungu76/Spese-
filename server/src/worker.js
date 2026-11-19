// Servizio di scambio tra l'app madre e l'app dell'altra persona (Cloudflare Worker + D1).
// Non interpreta mai i dati: conserva solo testi cifrati sul telefono. Ogni condivisione ha due codici (madre e ospite), salvati come impronta SHA-256.

const MAX_BLOB = 100_000;      // caratteri per un riepilogo o una spesa
const MAX_BODY = 400_000;      // caratteri per una richiesta
const MAX_ITEMS = 50;          // spese per richiesta
const MAX_INBOX = 5_000;       // spese in attesa per condivisione

const encoder = new TextEncoder();

const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status,
  headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
});
const unauthorized = () => json({ error: 'non autorizzato' }, 401);
const forbidden = () => json({ error: 'operazione non consentita a questo codice' }, 403);

function b64url(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
const randomToken = (bytes = 32) => b64url(crypto.getRandomValues(new Uint8Array(bytes)));

async function sha256Hex(text) {
  const buf = await crypto.subtle.digest('SHA-256', encoder.encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

/** 'owner' (app madre), 'guest' (altra persona) o null se il codice non vale per questa condivisione. */
async function roleOf(env, id, request) {
  const m = /^Bearer\s+(\S+)$/.exec(request.headers.get('authorization') || '');
  if (!m) return null;
  const hash = await sha256Hex(m[1]);
  const row = await env.DB.prepare('SELECT owner_hash, guest_hash FROM shares WHERE id = ?').bind(id).first();
  if (!row) return null;
  if (safeEqual(hash, row.owner_hash)) return 'owner';
  if (safeEqual(hash, row.guest_hash)) return 'guest';
  return null;
}

async function readBody(request) {
  const text = await request.text();
  if (text.length > MAX_BODY) return { tooLarge: true };
  try { return { value: JSON.parse(text) }; } catch { return { invalid: true }; }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split('/').filter(Boolean);
    const method = request.method;

    // "configured" dice solo se la chiave di creazione è visibile al servizio (mai il suo valore): serve a capire se il segreto è stato distribuito
    if (parts.length === 1 && parts[0] === 'health') return json({ ok: true, configured: Boolean(env.CREATE_KEY), database: Boolean(env.DB) });
    if (parts[0] !== 'v1' || parts[1] !== 'shares') return json({ error: 'non trovato' }, 404);

    // POST /v1/shares: crea una condivisione (serve la chiave di creazione impostata nel servizio)
    if (parts.length === 2) {
      if (method !== 'POST') return json({ error: 'metodo non consentito' }, 405);
      if (!env.CREATE_KEY) return json({ error: 'servizio non configurato' }, 503);
      if (!safeEqual(request.headers.get('x-create-key') || '', env.CREATE_KEY)) return unauthorized();
      const id = randomToken(16), ownerToken = randomToken(), guestToken = randomToken();
      await env.DB.prepare('INSERT INTO shares (id, owner_hash, guest_hash, created) VALUES (?, ?, ?, ?)')
        .bind(id, await sha256Hex(ownerToken), await sha256Hex(guestToken), Date.now()).run();
      return json({ id, ownerToken, guestToken });
    }

    const id = parts[2];
    const role = await roleOf(env, id, request);
    if (!role) return unauthorized();
    const sub = parts.slice(3).join('/');

    // DELETE /v1/shares/{id}: revoca (solo app madre)
    if (sub === '' && method === 'DELETE') {
      if (role !== 'owner') return forbidden();
      await env.DB.batch([
        env.DB.prepare('DELETE FROM inbox WHERE share_id = ?').bind(id),
        env.DB.prepare('DELETE FROM shares WHERE id = ?').bind(id),
      ]);
      return json({ revoked: true });
    }

    // riepilogo dall'app madre all'altra persona
    if (sub === 'out') {
      if (method === 'PUT') {
        if (role !== 'owner') return forbidden();
        const b = await readBody(request);
        if (b.tooLarge) return json({ error: 'troppo grande' }, 413);
        if (b.invalid || typeof b.value?.blob !== 'string' || !b.value.blob) return json({ error: 'richiesta non valida' }, 400);
        if (b.value.blob.length > MAX_BLOB) return json({ error: 'troppo grande' }, 413);
        const now = Date.now();
        await env.DB.prepare('UPDATE shares SET out_blob = ?, out_updated = ? WHERE id = ?').bind(b.value.blob, now, id).run();
        return json({ updated: now });
      }
      if (method === 'GET') {
        const row = await env.DB.prepare('SELECT out_blob, out_updated FROM shares WHERE id = ?').bind(id).first();
        if (!row || !row.out_blob) return json({ error: 'nessun dato' }, 404);
        const since = Number(url.searchParams.get('since'));
        if (since && since === row.out_updated) return json({ unchanged: true, updated: row.out_updated });
        return json({ blob: row.out_blob, updated: row.out_updated });
      }
      return json({ error: 'metodo non consentito' }, 405);
    }

    // spese rilevate dall'altro telefono all'app madre
    if (sub === 'in') {
      if (method === 'POST') {
        if (role !== 'guest') return forbidden();
        const b = await readBody(request);
        if (b.tooLarge) return json({ error: 'troppo grande' }, 413);
        const items = b.value?.items;
        if (b.invalid || !Array.isArray(items) || items.length === 0 || items.some((x) => typeof x !== 'string' || !x)) return json({ error: 'richiesta non valida' }, 400);
        if (items.length > MAX_ITEMS || items.some((x) => x.length > MAX_BLOB)) return json({ error: 'troppo grande' }, 413);
        const count = await env.DB.prepare('SELECT COUNT(*) AS n FROM inbox WHERE share_id = ?').bind(id).first();
        if ((count?.n ?? 0) + items.length > MAX_INBOX) return json({ error: 'troppe spese in attesa: apri l\'app madre' }, 429);
        const now = Date.now();
        await env.DB.batch(items.map((x) => env.DB.prepare('INSERT INTO inbox (share_id, blob, created) VALUES (?, ?, ?)').bind(id, x, now)));
        return json({ accepted: items.length });
      }
      if (method === 'GET') {
        if (role !== 'owner') return forbidden();
        const after = Math.max(0, Number(url.searchParams.get('after')) || 0);
        const limit = Math.min(200, Math.max(1, Number(url.searchParams.get('limit')) || 100));
        const { results } = await env.DB.prepare('SELECT seq, blob FROM inbox WHERE share_id = ? AND seq > ? ORDER BY seq LIMIT ?').bind(id, after, limit).all();
        return json({ items: results, last: results.length ? results[results.length - 1].seq : after });
      }
      return json({ error: 'metodo non consentito' }, 405);
    }

    // POST /v1/shares/{id}/in/ack: l'app madre ha importato fino a quel numero, si può liberare lo spazio
    if (sub === 'in/ack' && method === 'POST') {
      if (role !== 'owner') return forbidden();
      const b = await readBody(request);
      const upTo = Number(b.value?.upTo);
      if (b.invalid || !Number.isFinite(upTo) || upTo < 0) return json({ error: 'richiesta non valida' }, 400);
      await env.DB.prepare('DELETE FROM inbox WHERE share_id = ? AND seq <= ?').bind(id, upTo).run();
      return json({ ok: true });
    }

    return json({ error: 'non trovato' }, 404);
  },
};
