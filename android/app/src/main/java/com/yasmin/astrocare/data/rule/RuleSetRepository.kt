package com.yasmin.astrocare.data.rule

import android.content.Context
import com.yasmin.astrocare.domain.rule.ContractCatalog
import com.yasmin.astrocare.domain.rule.ContractCatalogLoader
import com.yasmin.astrocare.domain.rule.EvaluationContext
import com.yasmin.astrocare.domain.rule.EvaluationResult
import com.yasmin.astrocare.domain.rule.ExistingAlertRef
import com.yasmin.astrocare.domain.rule.ContextBuilder
import com.yasmin.astrocare.domain.rule.MeasurementRecord
import com.yasmin.astrocare.domain.rule.RuleEngine
import com.yasmin.astrocare.domain.rule.RuleSetLoader
import com.yasmin.astrocare.domain.rule.SymptomCheckInRecord
import com.yasmin.astrocare.domain.rule.model.TrendPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled contract files once (assets/contracts/) and runs rule
 * evaluations OFF the calling thread.
 *
 * Offline by construction: only local assets are read; no network client is
 * reachable from this class. First evaluation triggers load+parsing on
 * Dispatchers.Default — never on the main thread.
 */
@Singleton
class RuleSetRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val catalog: ContractCatalog by lazy {
        ContractCatalogLoader.load(readAsset(ENUMS_ASSET))
    }

    private val ruleSet: com.yasmin.astrocare.domain.rule.model.RuleSet by lazy {
        RuleSetLoader.load(readAsset(RULES_ASSET), catalog)
    }

    private val engine: RuleEngine by lazy { RuleEngine(ruleSet, catalog) }

    /** Contract ruleSetVersion (rules.v1.json) — loads the rule set if needed. */
    suspend fun ruleSetVersion(): String = withContext(Dispatchers.Default) {
        engine.ruleSetVersion
    }

    /** One full evaluation pass (load on first call), always off the main thread. */
    suspend fun evaluate(
        context: EvaluationContext,
        existing: List<ExistingAlertRef> = emptyList(),
    ): EvaluationResult = withContext(Dispatchers.Default) {
        engine.evaluate(context, existing)
    }

    /** Contract enums.json — metric defs, labels, units for Data Entry forms. */
    suspend fun contractCatalog(): ContractCatalog = withContext(Dispatchers.Default) {
        catalog
    }

    /** rules.v1.json `trendPolicy` — Trends math (window, baseline, direction, rounding). */
    suspend fun trendPolicy(): TrendPolicy = withContext(Dispatchers.Default) {
        ruleSet.trendPolicy
    }

    /**
     * Horizon (days) of rows the evaluator needs: the baseline window covers
     * both the 1440-minute context window and the insight history, so callers
     * fetch at least this many days of rows.
     */
    suspend fun baselineWindowDays(): Int = withContext(Dispatchers.Default) {
        ruleSet.trendPolicy.baseline.windowDays
    }

    /**
     * Builds an [EvaluationContext] from raw rows using the CONTRACT policies
     * (contextWindowMinutes, baseline window) — always off the main thread.
     */
    suspend fun buildContext(
        astronautId: String,
        nowMillis: Long,
        nowIso: String,
        measurements: List<MeasurementRecord>,
        checkIns: List<SymptomCheckInRecord>,
    ): EvaluationContext = withContext(Dispatchers.Default) {
        ContextBuilder(
            catalog = catalog,
            evaluationPolicy = ruleSet.evaluation,
            baselinePolicy = ruleSet.trendPolicy.baseline,
        ).build(
            astronautId = astronautId,
            nowMillis = nowMillis,
            nowIso = nowIso,
            measurements = measurements,
            checkIns = checkIns,
        )
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        const val ENUMS_ASSET = "contracts/enums.json"
        const val RULES_ASSET = "contracts/rules.v1.json"
    }
}
