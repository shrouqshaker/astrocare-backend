const { GROUPS } = require('../models');
const projection = { _id: 0, serverReceivedAt: 0, _sortTime: 0 };
function createHistoryService({ models }) {
  async function list(astronautId, kind, options) {
    const group = GROUPS.find(group => group.name === kind);
    const match = { astronautId };
    if (options.metric) match.metric = options.metric;
    if (options.level) match.level = options.level;
    const pipeline = [{ $match: match }, { $addFields: { _sortTime: { $toDate: `$${group.time}` } } }];
    if (options.from || options.to) {
      const range = {};
      if (options.from) range.$gte = new Date(options.from);
      if (options.to) range.$lte = new Date(options.to);
      pipeline.push({ $match: { _sortTime: range } });
    }
    pipeline.push({ $sort: { _sortTime: -1, id: 1 } }, { $limit: options.limit }, { $project: projection });
    const items = await models[group.modelName].aggregate(pipeline);
    return { items, count: items.length };
  }
  async function allMeasurements(astronautId, from, to) {
    return models.Measurement.aggregate([{ $match: { astronautId } }, { $addFields: { _sortTime: { $toDate: '$measuredAt' } } }, { $match: { _sortTime: { $gte: from, $lte: to } } }, { $sort: { _sortTime: 1, id: 1 } }, { $project: projection }]);
  }
  return { list, allMeasurements };
}
module.exports = { createHistoryService };
