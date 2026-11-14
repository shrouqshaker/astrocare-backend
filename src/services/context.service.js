// Shared context construction for overview and the reference-parity tests.
// Keep this in one place: the Android/Python reference evaluates the latest
// record per metric inside the 1,440-minute context window.
function buildContext(latestMeasurements, latestSymptomCheckin, rules, now) {
  const nowMs = typeof now === 'number' ? now : Date.parse(now);
  const cutoff = nowMs - rules.evaluation.contextWindowMinutes * 60000;
  const records = Object.fromEntries(
    latestMeasurements
      .filter(record => Date.parse(record.measuredAt) >= cutoff)
      .map(record => [record.metric, record])
  );
  const symptomRecord =
    latestSymptomCheckin && Date.parse(latestSymptomCheckin.checkedAt) >= cutoff
      ? latestSymptomCheckin
      : null;
  return {
    cutoff,
    context: {
      records,
      measurements: Object.fromEntries(
        Object.values(records).flatMap(record =>
          Object.entries(record.values).map(([key, value]) => [`${record.metric}.${key}`, value])
        )
      ),
      symptoms: symptomRecord?.symptoms || [],
      symptomRecord,
    },
  };
}

module.exports = { buildContext };
