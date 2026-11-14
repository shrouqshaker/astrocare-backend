const { GROUPS } = require('../models');
const { validateItem } = require('../validation');

async function insertOnce(Model, document) {
  try { await Model.create(document); return 'created'; }
  catch (error) { if (error.code !== 11000) throw error; return 'duplicate'; }
}
function createSyncService({ models, contracts, clock }) {
  async function syncBatch(batch) {
    const receivedAt = clock().toISOString();
    const response = { schemaVersion: '1.0', batchId: batch.batchId, receivedAt, serverTime: receivedAt, summary: { created: 0, duplicates: 0, rejected: 0 }, results: { measurements: [], symptomCheckins: [], alerts: [] }, latestRuleSetVersion: contracts.assets.rules.data.ruleSetVersion };
    for (const { name, kind, modelName } of GROUPS) {
      for (const item of batch.items[name]) {
        let error = validateItem(kind, item, contracts);
        if (!error && item.astronautId !== batch.astronautId) error = { code: 'ASTRONAUT_MISMATCH', message: 'Item astronautId must match the batch astronautId' };
        const result = { id: typeof item?.id === 'string' ? item.id : '', status: 'rejected' };
        if (error) { result.error = error; response.summary.rejected++; }
        else {
          result.status = await insertOnce(models[modelName], { ...item, serverReceivedAt: new Date(receivedAt) });
          if (kind === 'alert' && result.status === 'duplicate' && item.acknowledgedAt != null) {
            await models.Alert.updateOne({ astronautId: item.astronautId, id: item.id, acknowledgedAt: null }, { $set: { acknowledgedAt: item.acknowledgedAt } }, { collation: { locale: 'en', strength: 2 } });
          }
          response.summary[result.status === 'created' ? 'created' : 'duplicates']++;
        }
        response.results[name].push(result);
      }
    }
    // One audit per receipt, including replays: each summary records what this attempt did.
    await models.SyncBatch.create({ batchId: batch.batchId, deviceId: batch.deviceId, receivedAt, summary: response.summary });
    response.serverTime = clock().toISOString();
    return response;
  }
  return { syncBatch };
}
module.exports = { createSyncService, insertOnce };
