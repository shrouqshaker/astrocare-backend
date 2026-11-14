const { randomUUID } = require('node:crypto');
const { loadContracts } = require('../src/contracts');
const contracts = loadContracts();
function batch() {
  const value = contracts.read('examples/sync-request.json');
  value.batchId = randomUUID();
  for (const items of Object.values(value.items)) for (const item of items) item.id = randomUUID();
  return value;
}
module.exports = { contracts, batch };
