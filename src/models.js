const mongoose = require('mongoose');
const { validateItem } = require('./validation');

const GROUPS = [
  { name: 'measurements', modelName: 'Measurement', kind: 'measurement', time: 'measuredAt' },
  { name: 'symptomCheckins', modelName: 'SymptomCheckin', kind: 'symptom-checkin', time: 'checkedAt' },
  { name: 'alerts', modelName: 'Alert', kind: 'alert', time: 'createdAt' },
];
function createModels(connection, contracts) {
  const models = {};
  for (const group of GROUPS) {
    const definition = contracts.schemas[`${group.kind}.schema.json`];
    // Mixed leaves preserve JSON exactly; the immutable shared schema validates all nested keys and types.
    const properties = Object.fromEntries(Object.keys(definition.properties).map(key => [key, { type: mongoose.Schema.Types.Mixed }]));
    properties.serverReceivedAt = { type: Date, required: true, default: Date.now };
    const schema = new mongoose.Schema(properties, { strict: 'throw', versionKey: false, minimize: false });
    schema.index({ astronautId: 1, id: 1 }, { unique: true, collation: { locale: 'en', strength: 2 } });
    schema.index({ astronautId: 1, [group.time]: -1 });
    schema.pre('validate', function () {
      const { _id, serverReceivedAt, ...document } = this.toObject();
      const error = validateItem(group.kind, document, contracts);
      if (error) this.invalidate('document', error.message);
    });
    models[group.modelName] = connection.models[group.modelName] || connection.model(group.modelName, schema, group.name.toLowerCase());
  }
  const audit = new mongoose.Schema({ batchId: { type: String, required: true }, deviceId: { type: String, required: true }, receivedAt: { type: String, required: true }, summary: { created: Number, duplicates: Number, rejected: Number }, serverReceivedAt: { type: Date, default: Date.now } }, { strict: 'throw', versionKey: false });
  audit.index({ batchId: 1 });
  models.SyncBatch = connection.models.SyncBatch || connection.model('SyncBatch', audit, 'syncbatches');
  return models;
}
module.exports = { createModels, GROUPS };
