require('dotenv').config({ quiet: true });
const { loadConfig } = require('./config');
const { loadContracts } = require('./contracts');
const { connectDatabase } = require('./db');
const { createApp } = require('./app');
async function start() {
  const config = loadConfig();
  const contracts = loadContracts(config.CONTRACTS_DIR);
  const { connection, models } = await connectDatabase(config.MONGODB_URI, contracts);
  const logger = config.LOG_LEVEL === 'silent' ? {} : console;
  const app = createApp({ models, contracts, apiKey: config.API_KEY, isReady: () => connection.readyState === 1, logger });
  const server = app.listen(config.PORT, '0.0.0.0', () => {
    if (config.LOG_LEVEL === 'info') console.log(`AstroCare v${contracts.contractVersion} on port ${config.PORT}; no data is seeded automatically`);
  });
  const stop = () => {
    const timeout = setTimeout(() => process.exit(1), 10000).unref();
    server.close(async () => { await connection.close(); clearTimeout(timeout); });
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  server.on('error', async error => { console.error('Server failed:', error.code); await connection.close(); process.exitCode = 1; });
  return { server, connection };
}
if (require.main === module) start().catch(error => { console.error('Startup failed:', error.message); process.exitCode = 1; });
module.exports = { start };
