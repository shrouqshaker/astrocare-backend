const { parseEnvelope, checkAstronaut } = require('../validation');
function createSyncController({ syncService, contracts }) {
  return async (req, res) => {
    const batch = parseEnvelope(req.body);
    checkAstronaut(batch.astronautId, contracts);
    res.json(await syncService.syncBatch(batch));
  };
}
module.exports = { createSyncController };
