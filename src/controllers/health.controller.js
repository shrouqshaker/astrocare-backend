const { ApiError } = require('../lib/api-error');
function createHealthController({ isReady, contracts, clock }) {
  return (req, res) => {
    if (!isReady()) throw new ApiError(503, 'DB_UNAVAILABLE', 'MongoDB is unavailable');
    res.json({ status: 'ok', service: 'astrocare-backend', contractVersion: contracts.contractVersion, serverTime: clock().toISOString(), db: 'up' });
  };
}
module.exports = { createHealthController };
