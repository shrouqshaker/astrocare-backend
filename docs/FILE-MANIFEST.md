# Active implementation files

All paths are relative to the project root.

```text
.env.example
.gitignore
package.json
package-lock.json
README.md
TEST-RESULTS.md
example-sync.json
src/app.js
src/server.js
src/config.js
src/contracts.js
src/db.js
src/models.js
src/validation.js
src/lib/api-error.js
src/routes/index.js
src/routes/sync.routes.js
src/routes/astronauts.routes.js
src/controllers/health.controller.js
src/controllers/sync.controller.js
src/controllers/history.controller.js
src/controllers/assets.controller.js
src/controllers/demo.controller.js
src/controllers/analytics.controller.js
src/services/sync.service.js
src/services/history.service.js
src/services/seed.service.js
src/services/rules.service.js
src/services/trends.service.js
src/services/overview.service.js
src/middleware/security.js
src/middleware/error-handler.js
scripts/seed.js
scripts/verify-contracts.js
scripts/package.ps1
test/helpers.js
test/contracts.test.js
test/analytics.test.js
test/integration.test.js
docs/BACKEND-DECISIONS.md
docs/FILE-MANIFEST.md
docs/contracts-sha256.json
docs/upstream/CONTRACT_HANDOFF.md
docs/upstream/DATASETS.md
docs/upstream/OPEN_ISSUES.md
docs/upstream/VALIDATION_REPORT.md
contracts/**                      Exact supplied contracts, fixtures and vectors
dist/astrocare-backend-v1.zip       Generated runnable project ZIP (ignored by Git)
```

Workspace-only preserved materials, excluded from the project ZIP:

```text
archive/pre-contract-v1/**          Earlier implementation and its original docs/tests
shared/astrocare-contracts-v1/**    Original unpacked handoff bundle
```
