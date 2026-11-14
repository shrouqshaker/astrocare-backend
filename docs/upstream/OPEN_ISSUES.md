# Open issues for developer ownership

1. **Clinical references:** all alert-band thresholds remain illustrative and unverified. A medically qualified reviewer must review before any real use. These rules are for demo UI behavior only.
2. **Guide range semantics:** demo rule boundary rows are not equivalent to clinical normal ranges. UI must label them clearly.
3. **Trend test vectors:** supplied `trend-vectors.v1.json` contains aggregate fixtures, not full end-to-end TrendReport snapshots. AI/Data track must finalize full parity snapshots using its reference implementation.
4. **Seed period:** seed ends 2026-11-13; regenerate to the actual demonstration date if different.
5. **API/contract freeze:** `contracts/API.md` uses `/api/v1`, `/sync/batch`, and `id`; no track may substitute earlier example names without coordinating.
6. **Authentication:** X-API-Key inside a mobile client is demo-only; do not expose real health data.
7. **Android images:** guide asset names are specified but actual image/vector binaries belong to the Android track.
8. **Alerts vs symptoms:** shortness of breath may warrant urgent action even absent other measurements; current severity rules are demo definitions, not medical triage.
9. **Settings `apiKey`:** local demo configuration; do not treat as a production secret.
