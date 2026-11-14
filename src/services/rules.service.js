const { createHash } = require('node:crypto');
const compare = (observed, op, threshold) => ({ '<': observed < threshold, '<=': observed <= threshold, '>': observed > threshold, '>=': observed >= threshold, '==': observed === threshold })[op] === true;
function stableUuid(value) {
  const bytes = createHash('sha256').update(value).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 15) | 80;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
function evaluateRules(context, rules, enums, now) {
  function match(condition) {
    if (condition.all || condition.any) {
      const results = (condition.all || condition.any).map(match);
      const fires = condition.all ? results.every(result => result.fires) : results.some(result => result.fires);
      return { fires, evidence: fires ? results.filter(result => result.fires).flatMap(result => result.evidence) : [] };
    }
    if (condition.symptom) {
      const fires = context.symptoms.includes(condition.symptom);
      return { fires, evidence: fires ? [{ field: `symptom.${condition.symptom}`, observed: 1, unit: 'flag', comparator: '==', threshold: 1, observedAt: context.symptomRecord?.checkedAt || now, measurementId: null, symptomCheckinId: context.symptomRecord?.id || null }] : [] };
    }
    const observed = context.measurements[condition.field];
    const fires = typeof observed === 'number' && compare(observed, condition.op, condition.value);
    const [metric, field] = condition.field.split('.');
    const document = context.records?.[metric];
    return { fires, evidence: fires ? [{ field: condition.field, observed, unit: enums.metrics[metric].fields[field].unit, comparator: condition.op, threshold: condition.value, observedAt: document?.measuredAt || now, measurementId: document?.id || null, symptomCheckinId: null }] : [] };
  }
  const groups = new Map();
  for (const rule of rules.rules.filter(rule => rule.output === 'alert')) {
    const result = match(rule.when);
    if (!result.fires) continue;
    const previous = groups.get(rule.group);
    if (!previous || (rule.level === 'critical' && previous.rule.level !== 'critical')) groups.set(rule.group, { rule, evidence: result.evidence });
  }
  const matches = [...groups.values()];
  const metricsInContext = new Set(Object.keys(context.measurements).map(field => field.split('.')[0])).size;
  const status = matches.some(({ rule }) => rule.level === 'critical') ? 'critical' : matches.length ? 'warning' : metricsInContext < rules.statusPolicy.minMetricsForNormal ? 'insufficient_data' : 'normal';
  return { matches, overallStatus: { status, computedAt: now, firingRuleIds: matches.map(({ rule }) => rule.id), metricsInContext, ruleSetVersion: rules.ruleSetVersion } };
}
function makeAlert({ rule, evidence }, astronautId, context, rules, enums, now) {
  const measurementIds = [...new Set(evidence.map(entry => entry.measurementId).filter(Boolean))];
  const symptomCheckinIds = [...new Set(evidence.map(entry => entry.symptomCheckinId).filter(Boolean))];
  const template = rule.explanationTemplate || rules.evaluation.defaultExplanationTemplate;
  const explanation = template.replace(/\{([^}]+)\}/g, (placeholder, key) => {
    if (key === 'ruleId') return rule.id;
    const match = /^e(\d+)\.(\w+)$/.exec(key);
    if (!match) return placeholder;
    const entry = evidence[+match[1]];
    if (match[2] === 'label') return enums.fieldLabels[entry.field];
    if (match[2] === 'unit') return enums.unitDisplay[entry.unit];
    return String(entry[match[2]]);
  });
  const referenced = [...Object.values(context.records || {}), context.symptomRecord].filter(document => document && [...measurementIds, ...symptomCheckinIds].includes(document.id));
  const source = rules.sources[rule.sourceRef];
  return {
    schemaVersion: '1.0', id: stableUuid(`${astronautId}:${rule.id}:${measurementIds.join(',')}:${symptomCheckinIds.join(',')}`), astronautId, createdAt: now,
    level: rule.level, category: rule.category, ruleId: rule.id, ruleRevision: rule.revision, ruleSetVersion: rules.ruleSetVersion,
    title: rule.title, explanation, evidence, recommendedAction: rules.actions[rule.actionRef], disclaimer: rules.disclaimer,
    source: { ref: rule.sourceRef, title: source.title, url: source.url }, triggeredBy: { measurementIds, symptomCheckinIds },
    emergencyProtocolId: rule.emergencyProtocolId || null, acknowledgedAt: null,
    isSynthetic: referenced.length > 0 && referenced.every(document => document.isSynthetic),
  };
}
module.exports = { evaluateRules, makeAlert, compare, stableUuid };
