const { ApiError } = require('../lib/api-error');
function notFound(req, res, next) { next(new ApiError(404, 'NOT_FOUND', 'Route not found')); }
function createErrorHandler(logger = console) {
  return function errorHandler(error, req, res, next) {
    if (res.headersSent) return next(error);
    if (error.type === 'entity.parse.failed') error = new ApiError(400, 'VALIDATION_ERROR', 'Body must be valid JSON');
    if (error.type === 'entity.too.large') error = new ApiError(413, 'PAYLOAD_TOO_LARGE', 'JSON request exceeds 1 MB');
    if (error instanceof URIError) error = new ApiError(400, 'VALIDATION_ERROR', 'Invalid URL encoding');
    if (['MongoNetworkError', 'MongoServerSelectionError', 'MongoNotConnectedError', 'MongoTopologyClosedError'].includes(error.name)) error = new ApiError(503, 'DB_UNAVAILABLE', 'Database operation failed; partial writes may exist, so retry with the same IDs');
    if (!(error instanceof ApiError)) {
      logger.error?.({ requestId: req.requestId, error: error.name, code: error.code }, 'Request failed');
      error = new ApiError(500, 'INTERNAL_ERROR', 'The request could not be completed; retry sync with the same IDs because some records may have been saved');
    }
    const body = { code: error.code, message: error.message, requestId: req.requestId };
    if (error.details !== undefined) body.details = error.details;
    res.status(error.status).json({ error: body });
  };
}
module.exports = { notFound, createErrorHandler };
