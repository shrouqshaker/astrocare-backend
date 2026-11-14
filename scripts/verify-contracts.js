const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { loadContracts } = require('../src/contracts');
const { validateItem } = require('../src/validation');
const contracts = loadContracts();
const originalHashes = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../docs/contracts-sha256.json'), 'utf8').replace(/^\uFEFF/, ''));
for (const [file, expected] of Object.entries(originalHashes)) {
  const hash = createHash('sha256').update(fs.readFileSync(path.join(contracts.directory, file))).digest('hex');
  if (hash !== expected) throw new Error(`Shared contract changed: ${file}`);
}
for (const [kind, items] of [['measurement', contracts.seed.measurements], ['symptom-checkin', contracts.seed.symptomCheckins]]) for (const item of items) {
  const error = validateItem(kind, item, contracts);
  if (error) throw new Error(`${kind} ${item.id}: ${error.message}`);
}
for (const [file, schema] of [['measurement', 'measurement'], ['symptom-checkin', 'symptom-checkin'], ['alert', 'alert'], ['sync-request', 'sync-request'], ['sync-response', 'sync-response'], ['trend-report', 'trend-report'], ['guide-set', 'guide'], ['settings', 'settings'], ['error', 'error']]) {
  const validator = contracts.validators[`${schema}.schema.json`];
  if (!validator(contracts.read(`examples/${file}.json`))) throw new Error(`${file}: ${JSON.stringify(validator.errors)}`);
}
console.log('Shared examples, schemas and all synthetic seed records validate.');
