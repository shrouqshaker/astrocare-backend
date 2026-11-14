const express = require('express');
const { requestId } = require('./middleware/security');
const { notFound, createErrorHandler } = require('./middleware/error-handler');
const { createRoutes } = require('./routes');
const { createSyncService } = require('./services/sync.service');
const { createHistoryService } = require('./services/history.service');
const { createSeedService } = require('./services/seed.service');
const { createTrendsService } = require('./services/trends.service');
const { createOverviewService } = require('./services/overview.service');

function createApp({ models, contracts, isReady, apiKey, clock = () => new Date(), logger = console }) {
  if (!apiKey) throw new Error('An API key is required');
  const dependencies = { models, contracts, isReady, apiKey, clock };
  dependencies.historyService = createHistoryService(dependencies);
  dependencies.syncService = createSyncService(dependencies);
  dependencies.seedService = createSeedService(dependencies);
  dependencies.trendsService = createTrendsService(dependencies);
  dependencies.overviewService = createOverviewService(dependencies);
  const app = express();
  app.disable('x-powered-by');
  app.use(requestId);
  app.use('/api/v1', createRoutes(dependencies));
  app.use(notFound);
  app.use(createErrorHandler(logger));
  return app;
}
module.exports = { createApp };
