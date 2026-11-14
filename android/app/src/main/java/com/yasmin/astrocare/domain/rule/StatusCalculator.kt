package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.domain.rule.model.AlertLevel
import com.yasmin.astrocare.domain.rule.model.OverallStatusValue
import com.yasmin.astrocare.domain.rule.model.RuleSet

/**
 * rules.v1.json statusPolicy, applied in contract order:
 *
 *  1. critical  — any firing alert with level=critical
 *  2. warning   — else any firing alert with level=warning
 *  3. insufficient_data — else if fewer than minMetricsForNormal distinct
 *     metrics are in context
 *  4. normal    — otherwise
 *
 * Status is recomputed from the CURRENT context (the group-selected, pre-dedupe
 * candidates — dedupe concerns stored history and must not change status).
 * Insights never affect status. firingRuleIds are sorted for determinism.
 */
class StatusCalculator(private val ruleSet: RuleSet) {

    fun calculate(
        groupSelected: List<AlertDto>,
        metricsInContext: Int,
        computedAt: String,
    ): OverallStatus {
        val status = when {
            groupSelected.any { it.level == AlertLevel.CRITICAL } -> OverallStatusValue.critical
            groupSelected.any { it.level == AlertLevel.WARNING } -> OverallStatusValue.warning
            metricsInContext < ruleSet.statusPolicy.minMetricsForNormal ->
                OverallStatusValue.insufficient_data
            else -> OverallStatusValue.normal
        }
        return OverallStatus(
            status = status,
            computedAt = computedAt,
            firingRuleIds = groupSelected.map { it.ruleId }.sorted(),
            metricsInContext = metricsInContext,
            ruleSetVersion = ruleSet.ruleSetVersion,
        )
    }
}
