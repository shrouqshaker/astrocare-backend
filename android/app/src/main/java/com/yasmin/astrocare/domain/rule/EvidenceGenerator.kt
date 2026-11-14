package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.data.remote.dto.EvidenceDto

/**
 * Builds the `evidence` array of an alert from the TRUE leaves of a fired rule:
 * one item per TRUE leaf, in order of appearance in `when`
 * (contracts evaluation.evidenceOrder).
 *
 * Units and labels come from enums.json via [ContractCatalog] — never hardcoded.
 * Schema bounds: 1..6 items (alert.schema.json), enforced here.
 */
class EvidenceGenerator(private val catalog: ContractCatalog) {

    fun generate(matches: List<LeafMatch>): List<EvidenceDto> {
        require(matches.isNotEmpty()) { "evidence requires at least one TRUE leaf" }
        val evidence = matches.map { match ->
            when (match) {
                is FieldMatch -> EvidenceDto(
                    field = match.field,
                    observed = match.observed,
                    unit = catalog.unitFor(match.field),
                    comparator = match.op.symbol,
                    threshold = match.threshold,
                    observedAt = match.observedAt,
                    measurementId = match.measurementId,
                )

                is SymptomMatch -> EvidenceDto(
                    field = match.field,
                    observed = 1.0,
                    unit = "flag",
                    comparator = "==",
                    threshold = 1.0,
                    observedAt = match.observedAt,
                    symptomCheckinId = match.checkinId,
                )
            }
        }
        require(evidence.size in 1..6) { "evidence size ${evidence.size} outside schema 1..6" }
        return evidence
    }
}
