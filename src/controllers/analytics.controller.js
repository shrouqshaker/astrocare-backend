const { z } = require('zod');
const { parse, checkAstronaut, trendQuery } = require('../validation');
function createTrendsController({ trendsService, contracts }) {
  return async (req, res) => {
    checkAstronaut(req.params.astronautId, contracts);
    res.json(await trendsService.getTrends(req.params.astronautId, parse(trendQuery, req.query)));
  };
}
function createOverviewController({ overviewService, contracts }) {
  return async (req, res) => {
    checkAstronaut(req.params.astronautId, contracts);
    parse(z.strictObject({}), req.query);
    res.json(await overviewService.getOverview(req.params.astronautId));
  };
}
module.exports = { createTrendsController, createOverviewController };
