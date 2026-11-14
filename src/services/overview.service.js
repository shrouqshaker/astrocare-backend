const { evaluateRules, makeAlert } = require('./rules.service');
function createOverviewService({ models, historyService, contracts, clock }) {
  async function getOverview(astronautId) {
    const now = clock().toISOString();
    const [latestMeasurements, symptomHistory, measurements, symptomCheckins, alerts] = await Promise.all([
      models.Measurement.aggregate([{ $match: { astronautId } }, { $addFields: { _time: { $toDate: '$measuredAt' } } }, { $match: { _time: { $lte: new Date(now) } } }, { $sort: { _time: -1, id: 1 } }, { $group: { _id: '$metric', record: { $first: '$$ROOT' } } }, { $replaceRoot: { newRoot: '$record' } }, { $project: { _id: 0, serverReceivedAt: 0, _time: 0 } }, { $sort: { metric: 1 } }]),
      historyService.list(astronautId, 'symptomCheckins', { to: now, limit: 1 }),
      models.Measurement.countDocuments({ astronautId }), models.SymptomCheckin.countDocuments({ astronautId }), models.Alert.countDocuments({ astronautId }),
    ]);
    const rules = contracts.assets.rules.data;
    const cutoff = Date.parse(now) - rules.evaluation.contextWindowMinutes * 60000;
    const records = Object.fromEntries(latestMeasurements.filter(record => Date.parse(record.measuredAt) >= cutoff).map(record => [record.metric, record]));
    const latestSymptomCheckin = symptomHistory.items[0] || null;
    const symptomRecord = latestSymptomCheckin && Date.parse(latestSymptomCheckin.checkedAt) >= cutoff ? latestSymptomCheckin : null;
    const context = { records, measurements: Object.fromEntries(Object.values(records).flatMap(record => Object.entries(record.values).map(([key, value]) => [`${record.metric}.${key}`, value]))), symptoms: symptomRecord?.symptoms || [], symptomRecord };
    const evaluation = evaluateRules(context, rules, contracts.enums, now);
    const currentAlerts = evaluation.matches.map(match => makeAlert(match, astronautId, context, rules, contracts.enums, now));
    for (const alert of currentAlerts) if (!contracts.validators['alert.schema.json'](alert)) throw new Error('Generated overview alert violates shared schema');
    return { schemaVersion: '1.0', astronautId, generatedAt: now, latestMeasurements, latestSymptomCheckin, overallStatus: evaluation.overallStatus, alerts: currentAlerts, counts: { measurements, symptomCheckins, alerts } };
  }
  return { getOverview };
}
module.exports = { createOverviewService };
