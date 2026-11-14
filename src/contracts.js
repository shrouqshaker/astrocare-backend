const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const Ajv2020 = require('ajv/dist/2020');
const addFormats = require('ajv-formats');
const { z } = require('zod');

function loadContracts(directory = path.resolve(__dirname, '../contracts')) {
  const read = name => JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8'));
  const schemas = Object.fromEntries(fs.readdirSync(path.join(directory, 'schemas')).filter(name => name.endsWith('.json')).map(name => [name, read(`schemas/${name}`)]));
  const ajv = new Ajv2020({ allErrors: true, strict: false, validateFormats: true });
  addFormats(ajv);
  Object.values(schemas).forEach(schema => ajv.addSchema(schema));
  const validators = Object.fromEntries(Object.entries(schemas).map(([name, schema]) => [name, ajv.getSchema(schema.$id)]));
  // Zod validates items without changing, coercing or dropping any contract field.
  const items = Object.fromEntries(['measurement', 'symptom-checkin', 'alert'].map(name => [name, z.unknown().superRefine((value, ctx) => {
    const validate = validators[`${name}.schema.json`];
    if (!validate(value)) for (const issue of validate.errors) ctx.addIssue({ code: 'custom', path: [issue.instancePath || '/'], message: issue.message });
  })]));
  const assets = Object.fromEntries(['rules', 'guides'].map(name => {
    const raw = fs.readFileSync(path.join(directory, `data/${name}.v1.json`));
    return [name, { raw, etag: `"${createHash('sha256').update(raw).digest('hex')}"`, data: JSON.parse(raw) }];
  }));
  const enums = read('enums.json');
  const seed = read('data/synthetic-seed.v1.json');
  const contractVersion = fs.readFileSync(path.join(directory, 'CONTRACT_VERSION'), 'utf8').trim();
  if (contractVersion !== '1.0.0') throw new Error(`Unsupported contract version: ${contractVersion}`);
  if (!validators['guide.schema.json'](assets.guides.data)) throw new Error('Guide asset is incompatible with its schema');
  return { directory, read, schemas, validators, items, assets, enums, seed, contractVersion };
}
module.exports = { loadContracts };
