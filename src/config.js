const path = require('node:path');
const { z } = require('zod');

function loadConfig(env = process.env) {
  const schema = z.object({
    PORT: z.coerce.number().int().min(1).max(65535).default(3000),
    MONGODB_URI: z.string().regex(/^mongodb(?:\+srv)?:\/\//),
    API_KEY: z.string().min(1),
    CONTRACTS_DIR: z.string().default(path.resolve(__dirname, '../contracts')),
    PYTHON_BIN: z.string().default('python3'),
    LOG_LEVEL: z.enum(['silent', 'error', 'info']).default('info'),
  });
  const parsed = schema.safeParse(env);
  if (!parsed.success) throw new Error(`Invalid environment: ${parsed.error.issues.map(issue => issue.path.join('.')).join(', ')}`);
  return parsed.data;
}
module.exports = { loadConfig };
