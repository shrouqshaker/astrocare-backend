const { Router } = require('express');
const { createHistoryController } = require('../controllers/history.controller');
const { createTrendsController, createOverviewController } = require('../controllers/analytics.controller');
function createAstronautsRouter(dependencies) {
  const router = Router();
  for (const [path, kind] of [['measurements', 'measurements'], ['symptom-checkins', 'symptomCheckins'], ['alerts', 'alerts']]) router.get(`/:astronautId/${path}`, createHistoryController(dependencies, kind));
  router.get('/:astronautId/trends', createTrendsController(dependencies));
  router.get('/:astronautId/overview', createOverviewController(dependencies));
  return router;
}
module.exports = { createAstronautsRouter };
