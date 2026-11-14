const { z } = require('zod');
const { parse, checkAstronaut } = require('../validation');
function createDemoController({ seedService, contracts }) {
  return async (req, res) => {
    const body = parse(z.strictObject({ astronautId: z.string(), replace: z.boolean() }), req.body);
    checkAstronaut(body.astronautId, contracts);
    res.json(await seedService.seed(body));
  };
}
module.exports = { createDemoController };
