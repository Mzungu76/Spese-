import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import worker from '../src/worker.js';

// Un D1 finto sopra SQLite vero: le stesse istruzioni SQL del servizio girano davvero.
function fakeD1() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('../migrations/0001_init.sql', import.meta.url), 'utf8'));
  const prepare = (sql) => {
    const st = db.prepare(sql);
    return {
      bind: (...a) => ({
        first: async () => st.get(...a) ?? null,
        all: async () => ({ results: st.all(...a) }),
        run: async () => { const r = st.run(...a); return { meta: { changes: Number(r.changes) } }; },
      }),
    };
  };
  return { prepare, batch: async (stmts) => { for (const s of stmts) await s.run(); return []; } };
}

const KEY = 'chiave-di-creazione';
const env = () => ({ DB: fakeD1(), CREATE_KEY: KEY });
const call = (e, method, path, { token, body, headers = {} } = {}) => worker.fetch(new Request('https://x.test' + path, {
  method, headers: { ...(token ? { authorization: 'Bearer ' + token } : {}), ...headers }, body: body === undefined ? undefined : JSON.stringify(body),
}), e);
async function newShare(e) { const r = await call(e, 'POST', '/v1/shares', { headers: { 'x-create-key': KEY } }); assert.equal(r.status, 200); return r.json(); }

test('si crea una condivisione solo con la chiave di creazione', async () => {
  const e = env();
  assert.equal((await call(e, 'POST', '/v1/shares')).status, 401);
  assert.equal((await call(e, 'POST', '/v1/shares', { headers: { 'x-create-key': 'sbagliata' } })).status, 401);
  const s = await newShare(e);
  assert.ok(s.id && s.ownerToken && s.guestToken && s.ownerToken !== s.guestToken);
  assert.equal((await call({ DB: e.DB }, 'POST', '/v1/shares', { headers: { 'x-create-key': KEY } })).status, 503);   // servizio senza chiave: non configurato
});

test('il riepilogo va dall\'app madre all\'altra persona, e senza codice giusto non si vede', async () => {
  const e = env(); const s = await newShare(e); const p = `/v1/shares/${s.id}/out`;
  assert.equal((await call(e, 'GET', p, { token: s.guestToken })).status, 404);                        // ancora nulla
  assert.equal((await call(e, 'PUT', p, { token: s.guestToken, body: { blob: 'x' } })).status, 403);   // l'ospite non pubblica
  const put = await (await call(e, 'PUT', p, { token: s.ownerToken, body: { blob: 'v1.abc' } })).json();
  const got = await (await call(e, 'GET', p, { token: s.guestToken })).json();
  assert.equal(got.blob, 'v1.abc'); assert.equal(got.updated, put.updated);
  assert.deepEqual(await (await call(e, 'GET', p + '?since=' + got.updated, { token: s.guestToken })).json(), { unchanged: true, updated: got.updated });
  assert.equal((await call(e, 'GET', p)).status, 401);
  assert.equal((await call(e, 'GET', p, { token: 'a-caso' })).status, 401);
  const other = await newShare(e);
  assert.equal((await call(e, 'GET', p, { token: other.guestToken })).status, 401);                    // il codice di un'altra condivisione non vale
});

test('le spese vanno dall\'ospite all\'app madre, in ordine, e si liberano con la conferma', async () => {
  const e = env(); const s = await newShare(e); const p = `/v1/shares/${s.id}/in`;
  assert.equal((await call(e, 'POST', p, { token: s.ownerToken, body: { items: ['a'] } })).status, 403);
  assert.equal((await call(e, 'GET', p, { token: s.guestToken })).status, 403);
  assert.deepEqual(await (await call(e, 'POST', p, { token: s.guestToken, body: { items: ['uno', 'due'] } })).json(), { accepted: 2 });
  await call(e, 'POST', p, { token: s.guestToken, body: { items: ['tre'] } });
  const first = await (await call(e, 'GET', p, { token: s.ownerToken })).json();
  assert.deepEqual(first.items.map((x) => x.blob), ['uno', 'due', 'tre']);
  const later = await (await call(e, 'GET', p + '?after=' + first.items[1].seq, { token: s.ownerToken })).json();
  assert.deepEqual(later.items.map((x) => x.blob), ['tre']);
  assert.equal((await call(e, 'POST', p + '/ack', { token: s.guestToken, body: { upTo: first.last } })).status, 403);
  await call(e, 'POST', p + '/ack', { token: s.ownerToken, body: { upTo: first.last } });
  assert.deepEqual((await (await call(e, 'GET', p, { token: s.ownerToken })).json()).items, []);
});

test('limiti: richieste non valide, troppo grandi o troppe spese in attesa', async () => {
  const e = env(); const s = await newShare(e); const p = `/v1/shares/${s.id}/in`;
  assert.equal((await call(e, 'POST', p, { token: s.guestToken, body: { items: [] } })).status, 400);
  assert.equal((await call(e, 'POST', p, { token: s.guestToken, body: { items: [1] } })).status, 400);
  assert.equal((await call(e, 'POST', p, { token: s.guestToken, body: { items: Array(51).fill('x') } })).status, 413);
  assert.equal((await call(e, 'POST', p, { token: s.guestToken, body: { items: ['x'.repeat(100_001)] } })).status, 413);
  assert.equal((await call(e, 'PUT', `/v1/shares/${s.id}/out`, { token: s.ownerToken, body: { blob: 'x'.repeat(100_001) } })).status, 413);
  assert.equal((await call(e, 'PUT', `/v1/shares/${s.id}/out`, { token: s.ownerToken, body: {} })).status, 400);
  for (let i = 0; i < 100; i++) assert.equal((await call(e, 'POST', p, { token: s.guestToken, body: { items: Array(50).fill('x') } })).status, 200);   // 5.000 in coda
  assert.equal((await call(e, 'POST', p, { token: s.guestToken, body: { items: ['x'] } })).status, 429);
});

test('la revoca toglie tutto e i vecchi codici non valgono più', async () => {
  const e = env(); const s = await newShare(e);
  await call(e, 'PUT', `/v1/shares/${s.id}/out`, { token: s.ownerToken, body: { blob: 'x' } });
  await call(e, 'POST', `/v1/shares/${s.id}/in`, { token: s.guestToken, body: { items: ['a'] } });
  assert.equal((await call(e, 'DELETE', `/v1/shares/${s.id}`, { token: s.guestToken })).status, 403);
  assert.equal((await call(e, 'DELETE', `/v1/shares/${s.id}`, { token: s.ownerToken })).status, 200);
  assert.equal((await call(e, 'GET', `/v1/shares/${s.id}/out`, { token: s.guestToken })).status, 401);
  assert.equal((await call(e, 'GET', `/v1/shares/${s.id}/in`, { token: s.ownerToken })).status, 401);
});

test('percorsi e metodi sconosciuti', async () => {
  const e = env(); const s = await newShare(e);
  assert.equal((await call(e, 'GET', '/health')).status, 200);
  const h = await (await call(e, 'GET', '/health')).json();
  assert.equal(h.configured, true); assert.equal(h.database, true); assert.ok(!JSON.stringify(h).includes(KEY));   // mai il valore della chiave
  assert.equal((await (await call({ DB: e.DB }, 'GET', '/health')).json()).configured, false);
  assert.equal((await call(e, 'GET', '/altro')).status, 404);
  assert.equal((await call(e, 'GET', '/v1/shares')).status, 405);
  assert.equal((await call(e, 'POST', `/v1/shares/${s.id}/out`, { token: s.ownerToken })).status, 405);
  assert.equal((await call(e, 'GET', `/v1/shares/${s.id}/boh`, { token: s.ownerToken })).status, 404);
});
