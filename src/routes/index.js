const { Router, json } = require('express');
const { authenticate, requireReady } = require('../middleware/security');
const { createHealthController } = require('../controllers/health.controller');
const { createAssetsController } = require('../controllers/assets.controller');
const { createDemoController } = require('../controllers/demo.controller');
const { createSyncRouter } = require('./sync.routes');
const { createAstronautsRouter } = require('./astronauts.routes');
function createRoutes(dependencies) {
  const router = Router();
  router.get('/health', createHealthController(dependencies));
  router.use(authenticate(dependencies.apiKey));
  router.use(json({ limit: '1mb' }));
  // Static contracts remain readable even while MongoDB is down.
  router.get('/rules/latest', createAssetsController(dependencies, 'rules'));
  router.get('/guides/latest', createAssetsController(dependencies, 'guides'));
  router.use(requireReady(dependencies.isReady));
  router.use('/sync', createSyncRouter(dependencies));
  router.use('/astronauts', createAstronautsRouter(dependencies));
  router.post('/demo/seed', createDemoController(dependencies));
  return router;
}
module.exports = { createRoutes };
