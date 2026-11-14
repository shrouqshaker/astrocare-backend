const { parse, checkAstronaut, querySchema } = require('../validation');
function createHistoryController({ historyService, contracts }, kind) {
  return async (req, res) => {
    checkAstronaut(req.params.astronautId, contracts);
    const query = parse(querySchema(contracts, kind), req.query);
    res.json(await historyService.list(req.params.astronautId, kind, query));
  };
}
module.exports = { createHistoryController };
