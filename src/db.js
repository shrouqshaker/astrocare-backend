const mongoose = require('mongoose');
const { createModels } = require('./models');
async function connectDatabase(uri, contracts) {
  const connection = await mongoose.createConnection(uri, { serverSelectionTimeoutMS: 10000, autoIndex: true, bufferCommands: false }).asPromise();
  try {
    const models = createModels(connection, contracts);
    await Promise.all(Object.values(models).map(model => model.init()));
    return { connection, models };
  } catch (error) { await connection.close(); throw error; }
}
module.exports = { connectDatabase };
