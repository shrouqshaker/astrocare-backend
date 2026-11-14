const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { spawn } = require('node:child_process');
const net = require('node:net');
const { once } = require('node:events');
const request = require('supertest');
process.env.MONGOMS_RESUME_DOWNLOAD ??= 'true';
const { MongoMemoryServer } = require('mongodb-memory-server');
const { contracts, batch } = require('./helpers');
const { connectDatabase } = require('../src/db');
const { createApp } = require('../src/app');

let mongo, connection, models, app;
const key = 'test-only-key';
const now = new Date('2026-11-13T19:00:00Z');
const url = '/api/v1';
const astronaut = '/astronauts/demo-astronaut-01';
const get = path => request(app).get(`${url}${path}`).set('X-API-Key', key);
const post = (path, body) => request(app).post(`${url}${path}`).set('X-API-Key', key).send(body);
function matches(schema, body) {
  const validate = contracts.validators[`${schema}.schema.json`];
  assert.equal(validate(body), true, JSON.stringify(validate.errors));
}
before(async () => {
  mongo = await MongoMemoryServer.create();
  ({ connection, models } = await connectDatabase(mongo.getUri(`astrocare_test_${randomUUID().replaceAll('-', '')}`), contracts));
  app = createApp({ models, contracts, apiKey: key, isReady: () => connection.readyState === 1, clock: () => new Date(now), logger: {} });
}, { timeout: 600000 });
after(async () => { if (connection) await connection.close(); if (mongo) await mongo.stop(); });

test('no automatic seeding; health, authentication and request tracing follow contracts', async () => {
  assert.equal(await models.Measurement.countDocuments(), 0);
  const health = await request(app).get(`${url}/health`).expect(200);
  assert.deepEqual(health.body, { status: 'ok', service: 'astrocare-backend', contractVersion: '1.0.0', serverTime: now.toISOString(), db: 'up' });
  assert.ok(health.headers['x-request-id']);
  for (const path of ['/rules/latest', '/guides/latest', `${astronaut}/measurements`, `${astronaut}/symptom-checkins`, `${astronaut}/alerts`, `${astronaut}/trends`, `${astronaut}/overview`]) {
    const response = await request(app).get(`${url}${path}`).expect(401);
    matches('error', response.body);
    assert.equal(response.body.error.requestId, response.headers['x-request-id']);
  }
  await request(app).post(`${url}/sync/batch`).set('X-API-Key', 'wrong').send(batch()).expect(401);
  await request(app).post(`${url}/demo/seed`).send({}).expect(401);
  assert.deepEqual((await get(`${astronaut}/measurements`).expect(200)).body, { items: [], count: 0 });
  const overview = await get(`${astronaut}/overview`).expect(200);
  assert.equal(overview.body.overallStatus.status, 'insufficient_data');
  assert.deepEqual(overview.body.counts, { measurements: 0, symptomCheckins: 0, alerts: 0 });
});
test('sync persists schema documents unchanged, restores all three types and audits replays', async () => {
  const data = batch();
  const saved = await post('/sync/batch', data).expect(200);
  matches('sync-response', saved.body);
  assert.deepEqual(saved.body.summary, { created: 3, duplicates: 0, rejected: 0 });
  for (const [path, name, schema] of [['measurements', 'measurements', 'measurement'], ['symptom-checkins', 'symptomCheckins', 'symptom-checkin'], ['alerts', 'alerts', 'alert']]) {
    const history = await get(`${astronaut}/${path}`).expect(200);
    const stored = history.body.items.find(item => item.id === data.items[name][0].id);
    assert.deepEqual(stored, data.items[name][0]);
    matches(schema, stored);
  }
  const replay = await post('/sync/batch', data).expect(200);
  matches('sync-response', replay.body);
  assert.deepEqual(replay.body.summary, { created: 0, duplicates: 3, rejected: 0 });
  assert.equal(await models.SyncBatch.countDocuments({ batchId: data.batchId }), 2);
  const stored = await models.Measurement.collection.findOne({ id: data.items.measurements[0].id });
  assert.ok(stored.serverReceivedAt instanceof Date);
  assert.equal(stored.__v, undefined);
});
test('same ID with changed content remains duplicate; only first alert acknowledgement is added', async () => {
  const data = batch();
  await post('/sync/batch', data).expect(200);
  const changed = structuredClone(data);
  changed.items.measurements[0].values.bpm = 99;
  changed.items.alerts[0].acknowledgedAt = '2026-11-13T19:00:00Z';
  changed.items.alerts[0].title = 'Changed title';
  const response = await post('/sync/batch', changed).expect(200);
  assert.equal(response.body.summary.duplicates, 3);
  const measurement = await models.Measurement.findOne({ id: data.items.measurements[0].id }).lean();
  assert.equal(measurement.values.bpm, data.items.measurements[0].values.bpm);
  let alert = await models.Alert.findOne({ id: data.items.alerts[0].id }).lean();
  assert.equal(alert.title, data.items.alerts[0].title);
  assert.equal(alert.acknowledgedAt, changed.items.alerts[0].acknowledgedAt);
  changed.items.alerts[0].acknowledgedAt = '2026-11-13T20:00:00Z';
  await post('/sync/batch', changed).expect(200);
  await post('/sync/batch', data).expect(200);
  alert = await models.Alert.findOne({ id: data.items.alerts[0].id }).lean();
  assert.equal(alert.acknowledgedAt, '2026-11-13T19:00:00Z');
});
test('valid envelope saves good items and rejects invalid schema, BP, identity and version individually', async () => {
  const data = batch();
  const base = data.items.measurements[0];
  data.items.measurements.push(null, { ...base, id: randomUUID(), values: { bpm: '72' } }, { ...base, id: randomUUID(), metric: 'blood_pressure', values: { systolic: 90, diastolic: 100 } }, { ...base, id: randomUUID(), astronautId: 'other-astronaut' }, { ...base, id: randomUUID(), schemaVersion: '2.0' });
  const response = await post('/sync/batch', data).expect(200);
  matches('sync-response', response.body);
  assert.deepEqual(response.body.summary, { created: 3, duplicates: 0, rejected: 5 });
  assert.deepEqual(response.body.results.measurements.slice(1).map(item => item.error.code), ['VALIDATION_ERROR', 'VALIDATION_ERROR', 'CROSS_FIELD_INVALID', 'ASTRONAUT_MISMATCH', 'UNSUPPORTED_SCHEMA_VERSION']);
});
test('malformed envelopes, unsupported versions, JSON and oversized requests return shared errors', async () => {
  const data = batch();
  for (const body of [{}, [], { ...data, extra: true }, { ...data, items: { measurements: [] } }, { ...data, items: { ...data.items, measurements: Array(101).fill({}) } }]) {
    const response = await post('/sync/batch', body).expect(400);
    matches('error', response.body);
  }
  const version = await post('/sync/batch', { ...data, schemaVersion: '2.0' }).expect(400);
  assert.equal(version.body.error.code, 'UNSUPPORTED_SCHEMA_VERSION');
  matches('error', (await request(app).post(`${url}/sync/batch`).set('X-API-Key', key).set('Content-Type', 'application/json').send('{bad').expect(400)).body);
  const large = await post('/sync/batch', { data: 'x'.repeat(1024 * 1024) }).expect(413);
  assert.equal(large.body.error.code, 'PAYLOAD_TOO_LARGE');
  matches('error', large.body);
});
test('unique MongoDB indexes prevent duplicates across concurrent requests and UUID casing', async () => {
  const data = batch();
  const responses = await Promise.all(Array.from({ length: 8 }, () => post('/sync/batch', data).expect(200)));
  assert.equal(responses.reduce((sum, response) => sum + response.body.summary.created, 0), 3);
  assert.equal(responses.reduce((sum, response) => sum + response.body.summary.duplicates, 0), 21);
  for (const [Model, name] of [[models.Measurement, 'measurements'], [models.SymptomCheckin, 'symptomCheckins'], [models.Alert, 'alerts']]) assert.equal(await Model.countDocuments({ id: data.items[name][0].id }), 1);
  for (const items of Object.values(data.items)) items[0].id = items[0].id.toUpperCase();
  assert.equal((await post('/sync/batch', data).expect(200)).body.summary.duplicates, 3);
});
test('history filters and sorts true instants despite differing timestamp precision; bounds are inclusive', async () => {
  const data = batch();
  data.items = { measurements: ['2026-11-13T12:00:00Z', '2026-11-13T12:00:00.001Z', '2026-11-13T12:00:00.100Z'].map(measuredAt => ({ ...data.items.measurements[0], id: randomUUID(), metric: 'temperature', values: { celsius: 36.5 }, measuredAt })), symptomCheckins: [], alerts: [] };
  await post('/sync/batch', data).expect(200);
  const response = await get(`${astronaut}/measurements?metric=temperature&from=2026-11-13T12:00:00Z&to=2026-11-13T12:00:00.001Z&limit=1000`).expect(200);
  assert.deepEqual(response.body.items.map(item => item.measuredAt), ['2026-11-13T12:00:00.001Z', '2026-11-13T12:00:00Z']);
  assert.equal(response.body.count, 2);
  assert.equal((await get(`${astronaut}/measurements?metric=temperature&limit=1`)).body.items.length, 1);
  for (const query of ['limit=1001', 'metric=activity', 'from=2026-11-13', 'from=2026-02-30T00:00:00Z', 'from=2026-11-14T00:00:00Z&to=2026-11-13T00:00:00Z', 'limit=1&limit=2']) matches('error', (await get(`${astronaut}/measurements?${query}`).expect(400)).body);
  assert.ok((await get(`${astronaut}/alerts?level=warning&limit=1`)).body.items.every(alert => alert.level === 'warning'));
  await get('/astronauts/other-astronaut/measurements').expect(404);
});
test('rule and guide assets are byte-exact and support conditional requests', async () => {
  for (const name of ['rules', 'guides']) {
    const response = await get(`/${name}/latest`).expect(200);
    assert.equal(response.text, contracts.assets[name].raw.toString('utf8'));
    const cached = await get(`/${name}/latest`).set('If-None-Match', response.headers.etag).expect(304);
    assert.ok(cached.headers['x-request-id']);
    await get(`/${name}/latest`).set('If-None-Match', `W/${response.headers.etag}`).expect(304);
  }
});
test('explicit seed is idempotent, replacement preserves manual data and does not affect other astronauts', async () => {
  // Remove prior test docs to verify the fixture counts independently.
  await Promise.all([models.Measurement, models.SymptomCheckin, models.Alert].map(Model => Model.deleteMany({})));
  const manual = batch();
  manual.items = { measurements: [{ ...manual.items.measurements[0], source: 'manual', isSynthetic: false }], symptomCheckins: [], alerts: [] };
  await post('/sync/batch', manual).expect(200);
  const body = { astronautId: 'demo-astronaut-01', replace: false };
  assert.deepEqual((await post('/demo/seed', body).expect(200)).body, { inserted: { measurements: 126, symptomCheckins: 1 }, skipped: 0 });
  assert.deepEqual((await post('/demo/seed', body).expect(200)).body, { inserted: { measurements: 0, symptomCheckins: 0 }, skipped: 127 });
  await post('/demo/seed', { ...body, replace: true }).expect(200);
  assert.equal(await models.Measurement.countDocuments({ id: manual.items.measurements[0].id }), 1);
  assert.equal(await models.Measurement.countDocuments(), 127);
  await post('/demo/seed', { ...body, astronautId: 'other-astronaut' }).expect(404);
  await post('/demo/seed', { ...body, replace: 'true' }).expect(400);
});
test('trends and overview return real stored aggregates with evidence and current status', async () => {
  const trends = await get(`${astronaut}/trends?utcOffsetMinutes=0`).expect(200);
  matches('trend-report', trends.body);
  assert.equal(trends.body.generatedBy, 'backend_optional');
  assert.equal(trends.body.metrics.length, 5);
  assert.ok(trends.body.metrics.some(metric => metric.baseline.available));
  assert.ok(trends.body.metrics.flatMap(metric => metric.fields).some(field => field.points.some(point => point.count > 0)));
  await get(`${astronaut}/trends?utcOffsetMinutes=841`).expect(400);
  const data = batch();
  data.items.measurements = [{ ...data.items.measurements[0], values: { bpm: 150 }, measuredAt: now.toISOString() }];
  data.items.symptomCheckins = [{ ...data.items.symptomCheckins[0], symptoms: ['chest_pain'], checkedAt: now.toISOString() }];
  data.items.alerts = [];
  await post('/sync/batch', data).expect(200);
  const overview = (await get(`${astronaut}/overview`).expect(200)).body;
  assert.equal(overview.overallStatus.status, 'critical');
  const alert = overview.alerts.find(alert => alert.ruleId === 'HR-HIGH-C');
  matches('alert', alert);
  assert.equal(alert.evidence[0].observed, 150);
  assert.equal(alert.evidence[0].measurementId, data.items.measurements[0].id);
  assert.ok(overview.alerts.some(alert => alert.ruleId === 'SYM-CHEST-C'));
  // Overview evaluation does not silently insert alerts into uploaded alert history.
  assert.equal(await models.Alert.countDocuments(), 0);
});
test('database outage and unknown routes return schema-valid errors, assets stay available', async () => {
  const unavailable = createApp({ models, contracts, apiKey: key, isReady: () => false, clock: () => now, logger: {} });
  for (const path of ['/health', `${astronaut}/measurements`]) {
    const response = await request(unavailable).get(`${url}${path}`).set('X-API-Key', key).expect(503);
    matches('error', response.body);
    assert.equal(response.body.error.code, 'DB_UNAVAILABLE');
  }
  await request(unavailable).get(`${url}/rules/latest`).set('X-API-Key', key).expect(200);
  matches('error', (await get('/unknown').expect(404)).body);
});

async function freePort() {
  const server = net.createServer().listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function runChild(args, env) {
  const child = spawn(process.execPath, args, { cwd: process.cwd(), env: { ...process.env, ...env }, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', data => { output += data; });
  child.stderr.on('data', data => { output += data; });
  const timeout = setTimeout(() => child.kill(), 60000);
  try { const [code] = await once(child, 'exit'); return { code, output }; }
  finally { clearTimeout(timeout); }
}
test('explicit seed CLI runs against disposable MongoDB and replay skips every fixture', async () => {
  const env = { MONGODB_URI: mongo.getUri(`cli_${randomUUID().replaceAll('-', '')}`), API_KEY: key, CONTRACTS_DIR: './contracts', LOG_LEVEL: 'silent' };
  const first = await runChild(['scripts/seed.js'], env);
  assert.equal(first.code, 0, first.output);
  assert.equal(JSON.parse(first.output).inserted.measurements, 126);
  const second = await runChild(['scripts/seed.js'], env);
  assert.equal(second.code, 0, second.output);
  assert.equal(JSON.parse(second.output).skipped, 127);
});
test('real server starts after database/index initialization and persists across restart', async () => {
  const port = await freePort();
  const env = { ...process.env, PORT: String(port), MONGODB_URI: mongo.getUri(`server_${randomUUID().replaceAll('-', '')}`), API_KEY: key, CONTRACTS_DIR: './contracts', LOG_LEVEL: 'info' };
  const children = [];
  const launch = async () => {
    const child = spawn(process.execPath, ['src/server.js'], { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    children.push(child);
    await new Promise((resolve, reject) => {
      let output = '';
      const timeout = setTimeout(() => reject(new Error(`Server startup timeout: ${output}`)), 15000);
      child.stdout.on('data', data => { output += data; if (output.includes('on port')) { clearTimeout(timeout); resolve(); } });
      child.stderr.on('data', data => { output += data; });
      child.once('exit', code => { clearTimeout(timeout); reject(new Error(`Server exited ${code}: ${output}`)); });
    });
    return child;
  };
  const stop = async child => { const exited = once(child, 'exit'); child.kill(); await exited; };
  try {
    const child = await launch();
    const base = `http://127.0.0.1:${port}/api/v1`;
    assert.equal((await fetch(`${base}/health`)).status, 200);
    const data = batch();
    const response = await fetch(`${base}/sync/batch`, { method: 'POST', headers: { 'X-API-Key': key, 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).summary.created, 3);
    await stop(child);
    const second = await launch();
    const history = await fetch(`${base}${astronaut}/measurements`, { headers: { 'X-API-Key': key } });
    assert.equal((await history.json()).items[0].id, data.items.measurements[0].id);
    await stop(second);
  } finally { for (const child of children) if (child.exitCode === null && child.signalCode === null) child.kill(); }
});
