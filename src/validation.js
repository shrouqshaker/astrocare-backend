const { z } = require('zod');
const { ApiError } = require('./lib/api-error');

const uuid = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, 'Expected UUID');
const utc = z.iso.datetime({ precision: null }).refine(value => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/.test(value), 'Expected UTC timestamp ending in Z');
const astronaut = z.string().regex(/^[a-z0-9][a-z0-9-]{2,39}$/);
const envelope = z.strictObject({
  schemaVersion: z.literal('1.0'), batchId: uuid, astronautId: astronaut, deviceId: uuid,
  sentAt: utc, clientRuleSetVersion: z.string().regex(/^\d+\.\d+\.\d+$/),
  items: z.strictObject({ measurements: z.array(z.unknown()).max(100), symptomCheckins: z.array(z.unknown()).max(100), alerts: z.array(z.unknown()).max(100) }),
});
const details = issues => issues.map(issue => ({ path: issue.path.join('.'), message: issue.message }));
function parse(schema, input) {
  const result = schema.safeParse(input);
  if (!result.success) throw new ApiError(400, 'VALIDATION_ERROR', 'Request validation failed', details(result.error.issues));
  return result.data;
}
function parseEnvelope(body) {
  if (body && typeof body === 'object' && 'schemaVersion' in body && body.schemaVersion !== '1.0') throw new ApiError(400, 'UNSUPPORTED_SCHEMA_VERSION', 'Only schemaVersion 1.0 is supported');
  return parse(envelope, body);
}
function checkAstronaut(id, contracts) {
  parse(astronaut, id);
  if (id !== contracts.enums.defaultAstronautId) throw new ApiError(404, 'NOT_FOUND', 'Only demo-astronaut-01 is available in this demo');
}
function querySchema(contracts, kind) {
  const common = { from: utc.optional(), to: utc.optional(), limit: z.string().regex(/^[1-9]\d*$/).transform(Number).pipe(z.number().int().max(1000)).default(200) };
  if (kind === 'measurements') common.metric = z.enum(Object.keys(contracts.enums.metrics)).optional();
  if (kind === 'alerts') common.level = z.enum(contracts.enums.alertLevels).optional();
  return z.strictObject(common).refine(query => !query.from || !query.to || Date.parse(query.from) <= Date.parse(query.to), 'from must be <= to');
}
const trendQuery = z.strictObject({ utcOffsetMinutes: z.string().regex(/^-?\d+$/).transform(Number).pipe(z.number().int().min(-840).max(840)).default(0) });
function validateItem(kind, item, contracts) {
  if (item && typeof item === 'object' && 'schemaVersion' in item && item.schemaVersion !== '1.0') return { code: 'UNSUPPORTED_SCHEMA_VERSION', message: 'Only schemaVersion 1.0 is supported' };
  const result = contracts.items[kind].safeParse(item);
  if (!result.success) return { code: 'VALIDATION_ERROR', message: 'Item does not match the shared schema', details: details(result.error.issues) };
  // JSON Schema uses patterns for dates; Zod also rejects impossible calendar instants.
  const dates = [item.measuredAt, item.checkedAt, item.createdAt, item.acknowledgedAt, ...(item.evidence || []).map(entry => entry.observedAt)].filter(value => value != null);
  if (dates.some(value => !utc.safeParse(value).success)) return { code: 'VALIDATION_ERROR', message: 'Invalid UTC calendar timestamp' };
  if (kind === 'measurement' && item.metric === 'blood_pressure' && item.values.systolic <= item.values.diastolic) return { code: 'CROSS_FIELD_INVALID', message: 'Blood pressure requires systolic > diastolic' };
  return null;
}
module.exports = { parse, parseEnvelope, checkAstronaut, querySchema, trendQuery, validateItem, utc };
