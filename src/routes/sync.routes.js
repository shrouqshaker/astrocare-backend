const { Router } = require('express');
const { createSyncController } = require('../controllers/sync.controller');
function createSyncRouter(dependencies) {
  return Router().post('/batch', createSyncController(dependencies));
}
module.exports = { createSyncRouter };
