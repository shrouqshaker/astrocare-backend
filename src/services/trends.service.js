const { compare, stableUuid } = require('./rules.service');
const DAY = 86400000;
const mean = numbers => numbers.length ? numbers.reduce((sum, value) => sum + value, 0) / numbers.length : null;
const round = value => value === null ? null : Math.round((value + Number.EPSILON) * 100) / 100;
const sampleSd = numbers => numbers.length < 2 ? null : Math.sqrt(numbers.reduce((sum, value) => sum + (value - mean(numbers)) ** 2, 0) / (numbers.length - 1));
const localDay = (instant, offset) => new Date(Date.parse(instant) + offset * 60000).toISOString().slice(0, 10);

function buildTrendReport(astronautId, measurements, offset, now, contracts) {
  const rules = contracts.assets.rules.data;
  const policy = rules.trendPolicy;
  const endDay = localDay(now.toISOString(), offset);
  const days = Array.from({ length: 7 }, (_, index) => new Date(Date.parse(`${endDay}T00:00:00Z`) - (6 - index) * DAY).toISOString().slice(0, 10));
  const report = { schemaVersion: '1.0', astronautId, generatedAt: now.toISOString(), generatedBy: 'backend_optional', ruleSetVersion: rules.ruleSetVersion, window: { from: days[0], to: endDay, days: 7, utcOffsetMinutes: offset }, metrics: [], insights: [] };
  const rawFields = new Map();
  for (const [metric, definition] of Object.entries(contracts.enums.metrics)) {
    const records = measurements.filter(record => record.metric === metric && Date.parse(record.measuredAt) <= now.getTime()).sort((a, b) => Date.parse(a.measuredAt) - Date.parse(b.measuredAt) || a.id.localeCompare(b.id));
    const week = records.filter(record => days.includes(localDay(record.measuredAt, offset)));
    const baselineRecords = records.filter(record => Date.parse(record.measuredAt) >= now.getTime() - policy.baseline.windowDays * DAY && Date.parse(record.measuredAt) < now.getTime() - policy.baseline.excludeLastHours * 3600000);
    const distinctDays = new Set(baselineRecords.map(record => localDay(record.measuredAt, offset))).size;
    const available = baselineRecords.length >= policy.baseline.minSamples && distinctDays >= policy.baseline.minDistinctDays;
    const baseline = { available, windowDays: policy.baseline.windowDays, sampleCount: baselineRecords.length, distinctDays, reason: available ? null : 'Requires at least 5 samples across 3 distinct local days, excluding the last 24 hours' };
    const fields = [];
    for (const [key, fieldDefinition] of Object.entries(definition.fields)) {
      const field = `${metric}.${key}`;
      const points = days.map(date => {
        const values = week.filter(record => localDay(record.measuredAt, offset) === date).map(record => record.values[key]);
        return { date, count: values.length, min: values.length ? Math.min(...values) : null, mean: round(mean(values)), max: values.length ? Math.max(...values) : null };
      });
      // Compare unrounded daily means with equal weight per populated day.
      const dailyMeans = days.map(date => mean(week.filter(record => localDay(record.measuredAt, offset) === date).map(record => record.values[key])));
      const earlier = dailyMeans.slice(0, 4).filter(value => value !== null);
      const recent = dailyMeans.slice(4).filter(value => value !== null);
      let direction = 'insufficient_data';
      if (earlier.length >= policy.direction.minDaysWithDataPerSide && recent.length >= policy.direction.minDaysWithDataPerSide) {
        const delta = mean(recent) - mean(earlier);
        const band = policy.direction.stableBand[field];
        direction = delta > band + 1e-9 ? 'up' : delta < -band - 1e-9 ? 'down' : 'stable';
      }
      const latestRecord = week.at(-1);
      const baselineValues = baselineRecords.map(record => record.values[key]);
      const baselineMean = available ? mean(baselineValues) : null;
      const baselineSd = available ? sampleSd(baselineValues) : null;
      const latest = latestRecord ? latestRecord.values[key] : null;
      fields.push({ field, unit: fieldDefinition.unit, latest, latestAt: latestRecord?.measuredAt || null, weekMean: round(mean(week.map(record => record.values[key]))), baselineMean: round(baselineMean), baselineSd: round(baselineSd), deltaVsBaseline: latest !== null && available ? round(latest - baselineMean) : null, direction, points });
      rawFields.set(field, { latest, latestRecord, baselineMean, baselineSd, records: week, key, unit: fieldDefinition.unit });
    }
    report.metrics.push({ metric, baseline, fields });
  }
  for (const rule of rules.rules.filter(rule => rule.output === 'insight')) {
    const data = rawFields.get(rule.field);
    if (!data || data.latest === null) continue;
    let evidence;
    if (rule.type === 'baseline_deviation' && data.baselineMean !== null) {
      const scale = Math.max(data.baselineSd, rule.sdFloor);
      const zScore = (data.latest - data.baselineMean) / scale;
      if (Math.abs(zScore) < rule.zThreshold) continue;
      evidence = [{ field: rule.field, observed: data.latest, unit: data.unit, comparator: zScore >= 0 ? '>=' : '<=', threshold: round(data.baselineMean + Math.sign(zScore) * rule.zThreshold * scale), observedAt: data.latestRecord.measuredAt, measurementId: data.latestRecord.id, baselineMean: round(data.baselineMean), baselineSd: round(data.baselineSd), zScore: round(zScore) }];
    } else if (rule.type === 'window_average') {
      const last = data.records.slice(-rule.lastN);
      const average = mean(last.map(record => record.values[data.key]));
      if (last.length < rule.lastN || !compare(average, rule.op, rule.value)) continue;
      evidence = last.map(record => ({ field: rule.field, observed: record.values[data.key], unit: data.unit, comparator: rule.op, threshold: rule.value, observedAt: record.measuredAt, measurementId: record.id }));
    } else continue;
    report.insights.push({ id: stableUuid(`${astronautId}:${rule.id}:${evidence.map(entry => entry.measurementId).join(',')}`), ruleId: rule.id, ruleRevision: rule.revision, severity: rule.severity, metric: rule.metrics[0], text: rule.text, evidence, generatedAt: report.generatedAt });
  }
  if (!contracts.validators['trend-report.schema.json'](report)) throw new Error('Generated trend report violates shared schema');
  return report;
}
function createTrendsService({ historyService, contracts, clock }) {
  async function getTrends(astronautId, { utcOffsetMinutes }) {
    const now = clock();
    const records = await historyService.allMeasurements(astronautId, new Date(now.getTime() - 15 * DAY), now);
    return buildTrendReport(astronautId, records, utcOffsetMinutes, now, contracts);
  }
  return { getTrends };
}
module.exports = { createTrendsService, buildTrendReport, mean, sampleSd, localDay };
