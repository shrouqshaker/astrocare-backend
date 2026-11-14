const { randomUUID, timingSafeEqual } = require('node:crypto');
const { ApiError } = require('../lib/api-error');
function requestId(req, res, next) {
  // Generate server-owned IDs so arbitrary header text never enters logs or responses.
  req.requestId = randomUUID();
  res.set('X-Request-Id', req.requestId);
  next();
}
function authenticate(apiKey) {
  const expected = Buffer.from(apiKey);
  return (req, res, next) => {
    const incoming = Buffer.from(req.get('X-API-Key') || '');
    if (incoming.length !== expected.length || !timingSafeEqual(incoming, expected)) return next(new ApiError(401, 'UNAUTHORIZED', 'A valid X-API-Key header is required'));
    next();
  };
}
function requireReady(isReady) {
  return (req, res, next) => isReady() ? next() : next(new ApiError(503, 'DB_UNAVAILABLE', 'MongoDB is unavailable; retry the same batch IDs after recovery'));
}
module.exports = { requestId, authenticate, requireReady };
