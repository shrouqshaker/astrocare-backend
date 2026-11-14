const { test } = require('node:test');
const assert = require('node:assert/strict');
const { contracts, batch } = require('./helpers');
const { parseEnvelope, validateItem, parse, querySchema, trendQuery } = require('../src/validation');
const { loadConfig } = require('../src/config');

test('contract examples validate against their unmodified schemas', () => {
  for (const [name, schema] of [['measurement', 'measurement'], ['symptom-checkin', 'symptom-checkin'], ['alert', 'alert'], ['sync-request', 'sync-request'], ['sync-response', 'sync-response'], ['trend-report', 'trend-report'], ['guide-set', 'guide'], ['settings', 'settings'], ['error', 'error']]) {
    const validator = contracts.validators[`${schema}.schema.json`];
    assert.equal(validator(contracts.read(`examples/${name}.json`)), true, `${name}: ${JSON.stringify(validator.errors)}`);
  }
});
test('all seed items validate and are explicitly synthetic', () => {
  assert.equal(contracts.seed.measurements.length, 126);
  assert.equal(contracts.seed.symptomCheckins.length, 1);
  for (const [kind, items] of [['measurement', contracts.seed.measurements], ['symptom-checkin', contracts.seed.symptomCheckins]]) for (const item of items) {
    assert.equal(validateItem(kind, item, contracts), null);
    assert.equal(item.isSynthetic, true);
    assert.equal(item.source, 'synthetic');
  }
});
test('Zod envelope separates malformed batches from invalid individual items', () => {
  const data = batch();
  data.items.measurements.push(null, {}, 'bad');
  assert.doesNotThrow(() => parseEnvelope(data));
  for (const body of [{}, [], { ...data, extra: true }, { ...data, items: {} }, { ...data, sentAt: '2026-02-30T00:00:00Z' }, { ...data, items: { ...data.items, alerts: Array(101).fill({}) } }]) assert.throws(() => parseEnvelope(body));
  assert.throws(() => parseEnvelope({ ...data, schemaVersion: '2.0' }), error => error.code === 'UNSUPPORTED_SCHEMA_VERSION');
});
test('item validation rejects coercion, unknown fields, impossible dates and wrong versions', () => {
  const item = batch().items.measurements[0];
  for (const patch of [{ id: 'bad' }, { values: { bpm: '72' } }, { values: { bpm: 251 } }, { values: { bpm: 72.5 } }, { values: { bpm: 72, other: true } }, { clientId: item.id }, { measuredAt: '2026-11-13T10:00:00+02:00' }, { measuredAt: '2026-02-30T10:00:00Z' }, { source: 'simulated' }]) assert.ok(validateItem('measurement', { ...item, ...patch }, contracts));
  assert.equal(validateItem('measurement', { ...item, schemaVersion: '2.0' }, contracts).code, 'UNSUPPORTED_SCHEMA_VERSION');
  assert.equal(validateItem('measurement', { ...item, metric: 'blood_pressure', values: { systolic: 100, diastolic: 100 } }, contracts).code, 'CROSS_FIELD_INVALID');
  assert.equal(validateItem('symptom-checkin', { ...batch().items.symptomCheckins[0], symptoms: ['none', 'dizziness'] }, contracts).code, 'VALIDATION_ERROR');
});
test('history query limits, UTC ranges and enums are strict', () => {
  assert.equal(parse(querySchema(contracts, 'measurements'), {}).limit, 200);
  assert.equal(parse(querySchema(contracts, 'measurements'), { limit: '1000', metric: 'sleep' }).limit, 1000);
  for (const query of [{ limit: '0' }, { limit: '1001' }, { limit: '2.5' }, { metric: 'activity' }, { from: '2026-11-13' }, { from: ['2026-11-13T00:00:00Z'] }, { from: '2026-11-14T00:00:00Z', to: '2026-11-13T00:00:00Z' }]) assert.throws(() => parse(querySchema(contracts, 'measurements'), query));
  assert.equal(parse(trendQuery, {}).utcOffsetMinutes, 0);
  for (const query of [{ utcOffsetMinutes: '841' }, { utcOffsetMinutes: '0.5' }, { utcOffsetMinutes: 'NaN' }]) assert.throws(() => parse(trendQuery, query));
});
test('environment validation fails safely for missing secrets and invalid ports', () => {
  assert.equal(loadConfig({ MONGODB_URI: 'mongodb://localhost/demo', API_KEY: 'test' }).PORT, 3000);
  for (const env of [{}, { MONGODB_URI: 'mongodb://localhost/demo' }, { MONGODB_URI: 'https://bad', API_KEY: 'x' }, { MONGODB_URI: 'mongodb://localhost/demo', API_KEY: 'x', PORT: '0' }]) assert.throws(() => loadConfig(env));
});
