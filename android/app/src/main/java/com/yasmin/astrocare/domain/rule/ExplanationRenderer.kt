package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.data.remote.dto.EvidenceDto
import com.yasmin.astrocare.domain.rule.model.Rule
import java.util.Locale

/**
 * Renders an alert explanation from the rule's template (or
 * evaluation.defaultExplanationTemplate) using only the placeholders the
 * contract allows: ruleId, e{N}.label/observed/threshold/unit/comparator/
 * baselineMean/zScore (contracts evaluation.templatePlaceholders).
 *
 * Numbers are formatted with Locale.US (deterministic); means/SDs arrive
 * pre-rounded by [BaselineCalculator]. Units render through enums.json
 * unitDisplay ("percent" -> "%"). An unknown or unresolvable placeholder is
 * left LITERAL in the output so the defect stays visible — never invented.
 */
class ExplanationRenderer(private val catalog: ContractCatalog) {

    fun render(rule: Rule.Threshold, template: String, evidence: List<EvidenceDto>): String {
        var out = template.replace("{ruleId}", rule.id)
        PLACEHOLDER.findAll(template).forEach { match ->
            val full = match.value
            val name = match.groupValues[1]
            if (name == "ruleId") return@forEach // already replaced
            val index = E_INDEX.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
            val property = E_PROPERTY.matchEntire(name)?.groupValues?.get(1)
            if (index == null || property == null) return@forEach
            val item = evidence.getOrNull(index) ?: return@forEach // leave literal
            val rendered = when (property) {
                "label" -> catalog.labelFor(item.field)
                "observed" -> formatNumber(item.observed)
                "threshold" -> formatNumber(item.threshold)
                "unit" -> catalog.displayUnit(item.unit)
                "comparator" -> item.comparator
                "baselineMean" -> item.baselineMean?.let { formatNumber(it) }
                "baselineSd" -> item.baselineSd?.let { formatNumber(it) }
                "zScore" -> item.zScore?.let { formatNumber(it) }
                else -> null
            }
            if (rendered != null) out = out.replace(full, rendered)
        }
        return out
    }

    /** "39.0" -> "39"; "36.75" -> "36.75"; keeps contract rounding (2 decimals). */
    fun formatNumber(value: Double): String {
        val rounded = String.format(Locale.US, "%.2f", value)
        return if (rounded.endsWith(".00")) rounded.dropLast(3)
        else if (rounded.endsWith("0")) rounded.dropLast(1)
        else rounded
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{([^{}]+)\}""")
        val E_INDEX = Regex("""^e(\d+)\.[a-zA-Z]+$""")
        val E_PROPERTY = Regex("""^e\d+\.([a-zA-Z]+)$""")
    }
}
