const { validateItem } = require('../validation');
const { insertOnce } = require('./sync.service');
function createSeedService({ models, contracts }) {
  async function seed({ astronautId, replace }) {
    // Validate the entire fixture before any deletion or insertion.
    for (const [kind, items] of [['measurement', contracts.seed.measurements], ['symptom-checkin', contracts.seed.symptomCheckins]]) {
      for (const item of items) {
        const error = validateItem(kind, item, contracts);
        if (error || item.astronautId !== astronautId || !item.isSynthetic || item.source !== 'synthetic') throw new Error(`Invalid seed fixture: ${error?.message || 'identity/provenance mismatch'}`);
      }
    }
    if (replace) await Promise.all([models.Measurement, models.SymptomCheckin, models.Alert].map(Model => Model.deleteMany({ astronautId, isSynthetic: true })));
    const result = { inserted: { measurements: 0, symptomCheckins: 0 }, skipped: 0 };
    for (const [name, Model] of [['measurements', models.Measurement], ['symptomCheckins', models.SymptomCheckin]]) {
      for (const item of contracts.seed[name]) {
        if (await insertOnce(Model, item) === 'created') result.inserted[name]++;
        else result.skipped++;
      }
    }
    return result;
  }
  return { seed };
}
module.exports = { createSeedService };
