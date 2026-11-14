const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { contracts } = require('./helpers');
const { evaluateRules, makeAlert } = require('../src/services/rules.service');
const { buildTrendReport } = require('../src/services/trends.service');
const rules = contracts.assets.rules.data;
for (const vector of contracts.read('test-vectors/rule-vectors.v1.json').vectors) {
  test(`rule vector: ${vector.name}`, () => {
    const result = evaluateRules(vector.context, rules, contracts.enums, vector.now);
    assert.equal(result.overallStatus.status, vector.expected.status);
    const simplify = items => items.map(item => `${item.ruleId}:${item.level}`).sort();
    assert.deepEqual(simplify(result.matches.map(({ rule }) => ({ ruleId: rule.id, level: rule.level }))), simplify(vector.expected.alerts));
    for (const match of result.matches) assert.equal(contracts.validators['alert.schema.json'](makeAlert(match, contracts.enums.defaultAstronautId, vector.context, rules, contracts.enums, vector.now)), true);
  });
}
for (const vector of contracts.read('test-vectors/trend-vectors.v1.json').vectors) {
  test(`trend vector: ${vector.name}`, () => {
    const measurements = vector.input.dailyValues.flatMap(day => day.values.map((value, index) => ({ id: randomUUID(), metric: vector.input.metric, values: { [vector.input.field]: value, ...(vector.input.metric === 'sleep' ? { quality: 4 } : {}) }, measuredAt: `${day.date}T12:${String(index).padStart(2, '0')}:00Z` })));
    const report = buildTrendReport(contracts.enums.defaultAstronautId, measurements, vector.utcOffsetMinutes, new Date(vector.now), contracts);
    const field = report.metrics.find(metric => metric.metric === vector.input.metric).fields.find(field => field.field.endsWith(`.${vector.input.field}`));
    assert.equal(field.points.length, vector.expected.dailyCount);
    assert.equal(field.weekMean, vector.expected.weekMean);
    assert.equal(field.latest, vector.expected.latest);
    assert.equal(field.latestAt.slice(0, 10), vector.expected.latestAtDate);
    if (vector.expected.missingDays) assert.equal(field.points.filter(point => point.count === 0).length, vector.expected.missingDays);
  });
}
test('empty trends return seven null points per field and no manufactured observations', () => {
  const report = buildTrendReport('demo-astronaut-01', [], 120, new Date('2026-11-13T23:00:00Z'), contracts);
  assert.equal(report.window.to, '2026-11-14');
  assert.equal(report.metrics.length, 5);
  for (const metric of report.metrics) for (const field of metric.fields) {
    assert.equal(field.latest, null);
    assert.equal(field.direction, 'insufficient_data');
    assert.ok(field.points.every(point => point.count === 0 && point.mean === null));
  }
  assert.deepEqual(report.insights, []);
});
test('baseline uses sample SD, excludes last 24h, and emits traceable deviation evidence', () => {
  const now = new Date('2026-11-13T18:00:00Z');
  const records = [7, 8, 9, 10, 11].map((day, index) => ({ id: randomUUID(), metric: 'heart_rate', values: { bpm: 68 + index }, measuredAt: `2026-11-${String(day).padStart(2, '0')}T12:00:00Z` }));
  records.push({ id: randomUUID(), metric: 'heart_rate', values: { bpm: 100 }, measuredAt: now.toISOString() });
  const report = buildTrendReport('demo-astronaut-01', records, 0, now, contracts);
  const metric = report.metrics[0];
  assert.equal(metric.baseline.sampleCount, 5);
  assert.equal(metric.baseline.distinctDays, 5);
  assert.equal(metric.fields[0].baselineMean, 70);
  assert.equal(metric.fields[0].baselineSd, 1.58);
  const insight = report.insights.find(insight => insight.ruleId === 'INS-BASE-HR');
  assert.equal(insight.evidence[0].measurementId, records.at(-1).id);
  assert.equal(insight.evidence[0].zScore, 6);
});
test('sleep insight requires three logged nights and direction respects missing-data minimums', () => {
  const records = [11, 12, 13].map(day => ({ id: randomUUID(), metric: 'sleep', values: { hours: 5, quality: 2 }, measuredAt: `2026-11-${day}T06:00:00Z` }));
  const report = buildTrendReport('demo-astronaut-01', records, 0, new Date('2026-11-13T18:00:00Z'), contracts);
  assert.equal(report.insights.find(insight => insight.ruleId === 'INS-TRND-SLEEP').evidence.length, 3);
  assert.equal(report.metrics.find(metric => metric.metric === 'sleep').fields[0].direction, 'insufficient_data');
});

test('direction uses earlier four vs recent three local days, with inclusive stable bands', () => {
  const make = (early, recent) => Array.from({ length: 7 }, (_, index) => ({ id: randomUUID(), metric: 'temperature', values: { celsius: index < 4 ? early : recent }, measuredAt: `2026-11-${String(index + 7).padStart(2, '0')}T12:00:00Z` }));
  const direction = records => buildTrendReport('demo-astronaut-01', records, 0, new Date('2026-11-13T18:00:00Z'), contracts).metrics.find(metric => metric.metric === 'temperature').fields[0].direction;
  assert.equal(direction(make(36.6, 36.9)), 'stable');
  assert.equal(direction(make(36.6, 37.0)), 'up');
  assert.equal(direction(make(37.0, 36.5)), 'down');
});
