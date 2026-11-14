require('dotenv').config({ quiet: true });
const { loadConfig } = require('../src/config');
const { loadContracts } = require('../src/contracts');
const { connectDatabase } = require('../src/db');
const { createSeedService } = require('../src/services/seed.service');
async function main() {
  const args = process.argv.slice(2);
  if (args.some(arg => arg !== '--replace')) throw new Error('Usage: npm run seed -- [--replace]');
  const config = loadConfig();
  const contracts = loadContracts(config.CONTRACTS_DIR);
  const { connection, models } = await connectDatabase(config.MONGODB_URI, contracts);
  try { console.log(JSON.stringify(await createSeedService({ models, contracts }).seed({ astronautId: contracts.enums.defaultAstronautId, replace: args.includes('--replace') }), null, 2)); }
  finally { await connection.close(); }
}
main().catch(error => { console.error('Seed failed:', error.message); process.exitCode = 1; });
