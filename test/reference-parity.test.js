const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { contracts } = require('./helpers');
const { evaluateRules, makeAlert, stableUuid } = require('../src/services/rules.service');
const { buildContext } = require('../src/services/context.service');
const { buildTrendReport } = require('../src/services/trends.service');

const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures/reference-parity.v1.json'), 'utf8'));

function latestContext(measurements, checkins, now) {
  const latestByMetric = {};
  for (const measurement of measurements) {
    if (Date.parse(measurement.measuredAt) > Date.parse(now)) continue;
    if (!latestByMetric[measurement.metric] || Date.parse(measurement.measuredAt) >= Date.parse(latestByMetric[measurement.metric].measuredAt)) latestByMetric[measurement.metric] = measurement;
  }
  const latestCheckin = checkins.filter(item => Date.parse(item.checkedAt) <= Date.parse(now)).sort((a, b) => Date.parse(b.checkedAt) - Date.parse(a.checkedAt))[0] || null;
  return buildContext(Object.values(latestByMetric), latestCheckin, contracts.assets.rules.data, now).context;
}

test('UUIDv5 namespace and name layout match Python/Kotlin', () => {
  // uuid.uuid5(UUID('6f5d1a0e-6f2c-4f4b-9a0f-1d7c2b3e4a55'), 'test')
  assert.equal(stableUuid('test'), '0565a3b2-100b-5ef6-ba25-fd0842c4fb01');
});

test('critical chest-pain demo matches the frozen Python reference exactly', () => {
  const now = fixture.now;
  const context = latestContext(fixture.measurements, fixture.symptomCheckins, now);
  const evaluation = evaluateRules(context, contracts.assets.rules.data, contracts.enums, now);
  const alerts = evaluation.matches.map(match => makeAlert(match, contracts.enums.defaultAstronautId, context, contracts.assets.rules.data, contracts.enums, now));

  assert.equal(evaluation.overallStatus.status, fixture.expected.status);
  assert.deepEqual(alerts, fixture.expected.alerts);
  for (const alert of alerts) assert.equal(contracts.validators['alert.schema.json'](alert), true);

  const report = buildTrendReport(contracts.enums.defaultAstronautId, fixture.measurements, 0, new Date(now), contracts);
  assert.equal(report.generatedAt, now);
  assert.equal(report.generatedBy, 'backend_optional');
  assert.equal(report.ruleSetVersion, fixture.expected.ruleSetVersion);
  assert.deepEqual(report.window, fixture.expected.window);
  assert.deepEqual(report.metrics, fixture.expected.metrics);
  assert.deepEqual(report.insights, fixture.expected.insights);
  assert.equal(contracts.validators['trend-report.schema.json'](report), true);
});
