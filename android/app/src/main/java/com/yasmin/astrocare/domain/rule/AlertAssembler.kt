package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.AlertSourceDto
import com.yasmin.astrocare.data.remote.dto.TriggeredByDto
import com.yasmin.astrocare.domain.rule.model.AlertLevel
import com.yasmin.astrocare.domain.rule.model.Rule
import com.yasmin.astrocare.domain.rule.model.RuleSet

/** One fired threshold rule with its evaluated condition. */
data class FiringThreshold(val rule: Rule.Threshold, val condition: ConditionResult)

private data class Candidate(val rule: Rule.Threshold, val alert: AlertDto)

/**
 * Turns fired threshold rules into contract Alert DTOs and applies the two
 * contract policies from `rules.v1.json` evaluation block:
 *
 *  - groupPolicy — "within one group only the highest-level firing alert is
 *    emitted" (critical beats warning; ties keep the first rule in file order);
 *  - dedupe — "do not create an alert if an alert with the same ruleId and
 *    identical triggeredBy ids already exists".
 *
 * Output is sorted by ruleId (deterministic order). Suppressed candidates are
 * reported as notes — nothing is dropped silently.
 */
class AlertAssembler(
    private val ruleSet: RuleSet,
    private val evidenceGenerator: EvidenceGenerator,
    private val renderer: ExplanationRenderer,
) {

    fun assemble(
        firing: List<FiringThreshold>,
        context: EvaluationContext,
        existing: List<ExistingAlertRef>,
    ): AssembleOutcome {
        val candidates = firing.map { buildAlert(it, context) }

        val (selected, suppressed) = applyGroupPolicy(candidates)
        val (fresh, deduped) = applyDedupe(selected, existing)

        val notes = buildList {
            suppressed.forEach {
                add(
                    "groupPolicy: suppressed ${it.rule.id} (${it.rule.level}) in group " +
                        "'${it.rule.group}' — higher-level alert emitted instead",
                )
            }
            deduped.forEach {
                add("dedupe: ${it.rule.id} not created — same ruleId and identical triggeredBy ids exist")
            }
        }

        return AssembleOutcome(
            alerts = fresh.map { it.alert }.sortedBy { it.ruleId },
            groupSelected = selected.map { it.alert }.sortedBy { it.ruleId },
            notes = notes,
        )
    }

    private fun buildAlert(firing: FiringThreshold, context: EvaluationContext): Candidate {
        val rule = firing.rule
        val evidence = evidenceGenerator.generate(firing.condition.matches)
        val template = rule.explanationTemplate ?: ruleSet.evaluation.defaultExplanationTemplate
        val explanation = renderer.render(rule, template, evidence)
        val source = ruleSet.source(rule.sourceRef)

        val measurementIds = evidence.mapNotNull { it.measurementId }.distinct().sorted()
        val checkinIds = evidence.mapNotNull { it.symptomCheckinId }.distinct().sorted()

        val alert = AlertDto(
            schemaVersion = ruleSet.schemaVersion,
            id = StableUuid.alertId(
                astronautId = context.astronautId,
                ruleId = rule.id,
                revision = rule.revision,
                measurementIds = measurementIds,
                symptomCheckinIds = checkinIds,
            ),
            astronautId = context.astronautId,
            createdAt = context.nowIso,
            level = rule.level,
            category = rule.category,
            ruleId = rule.id,
            ruleRevision = rule.revision,
            ruleSetVersion = ruleSet.ruleSetVersion,
            title = rule.title,
            explanation = explanation,
            evidence = evidence,
            recommendedAction = ruleSet.action(rule.actionRef),
            disclaimer = ruleSet.disclaimer,
            source = AlertSourceDto(ref = rule.sourceRef, title = source.title, url = source.url),
            triggeredBy = TriggeredByDto(
                measurementIds = measurementIds,
                symptomCheckinIds = checkinIds,
            ),
            emergencyProtocolId = rule.emergencyProtocolId,
            isSynthetic = context.isSynthetic,
        )
        return Candidate(rule, alert)
    }

    private fun applyGroupPolicy(candidates: List<Candidate>): Pair<List<Candidate>, List<Candidate>> {
        val winners = LinkedHashMap<String, Candidate>()
        val suppressed = mutableListOf<Candidate>()
        for (candidate in candidates) {
            val incumbent = winners[candidate.rule.group]
            if (incumbent == null) {
                winners[candidate.rule.group] = candidate
            } else if (AlertLevel.rank(candidate.rule.level) > AlertLevel.rank(incumbent.rule.level)) {
                suppressed += incumbent
                winners[candidate.rule.group] = candidate
            } else {
                // Equal or lower level: incumbent (earlier in rules file) wins.
                suppressed += candidate
            }
        }
        return winners.values.toList() to suppressed
    }

    private fun applyDedupe(
        selected: List<Candidate>,
        existing: List<ExistingAlertRef>,
    ): Pair<List<Candidate>, List<Candidate>> {
        val fresh = mutableListOf<Candidate>()
        val deduped = mutableListOf<Candidate>()
        for (candidate in selected) {
            val triggered = candidate.alert.triggeredBy
            val alreadyExists = existing.any { ref ->
                ref.ruleId == candidate.alert.ruleId &&
                    ref.measurementIds == triggered.measurementIds.toSet() &&
                    ref.symptomCheckinIds == triggered.symptomCheckinIds.toSet()
            }
            if (alreadyExists) deduped += candidate else fresh += candidate
        }
        return fresh to deduped
    }
}

data class AssembleOutcome(
    /** After group policy AND dedupe — what actually gets stored. */
    val alerts: List<AlertDto>,
    /** After group policy, BEFORE dedupe — drives overall status. */
    val groupSelected: List<AlertDto>,
    val notes: List<String>,
)
