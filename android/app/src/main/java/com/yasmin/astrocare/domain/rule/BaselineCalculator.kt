package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.model.BaselinePolicy
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

data class BaselineStats(
    val mean: Double,
    val sd: Double,
    val samples: Int,
    val distinctDays: Int,
    val sufficient: Boolean,
    /** Non-null only when [sufficient] is false — why the baseline was rejected. */
    val insufficientReason: String?,
)

/**
 * Pure baseline math for insight rules, following rules.v1.json trendPolicy:
 *
 *  - window: [now - baseline.windowDays, now - baseline.excludeLastHours)
 *  - minimums: minSamples readings AND minDistinctDays distinct UTC days
 *  - method: mean and SAMPLE standard deviation (n-1) per field
 *  - rounding: means and SDs rounded to trendPolicy.roundingDecimals
 *
 * Deterministic: UTC day indexes from epoch millis, HALF_UP rounding.
 */
object BaselineCalculator {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val HOUR_MILLIS = 60L * 60 * 1000

    fun calculate(
        readingsNewestFirst: List<ContextMeasurement>,
        policy: BaselinePolicy,
        nowMillis: Long,
        roundingDecimals: Int,
    ): BaselineStats {
        val from = nowMillis - policy.windowDays * DAY_MILLIS
        val until = nowMillis - policy.excludeLastHours * HOUR_MILLIS
        val windowed = readingsNewestFirst.filter { it.observedAtMillis in from until until }

        val values = windowed.map { it.value }
        val samples = values.size
        val distinctDays = windowed.map { it.observedAtMillis / DAY_MILLIS }.distinct().size

        val mean = if (samples == 0) 0.0 else values.sum() / samples
        val sd = if (samples >= 2) {
            val variance = values.sumOf { (it - mean) * (it - mean) } / (samples - 1)
            sqrt(variance)
        } else {
            0.0
        }

        val sufficient = samples >= policy.minSamples && distinctDays >= policy.minDistinctDays
        val reason = if (sufficient) {
            null
        } else {
            "baseline needs >= ${policy.minSamples} samples in >= ${policy.minDistinctDays} distinct days " +
                "(window ${policy.windowDays}d minus last ${policy.excludeLastHours}h), " +
                "had $samples samples across $distinctDays day(s)"
        }

        return BaselineStats(
            mean = round(mean, roundingDecimals),
            sd = round(sd, roundingDecimals),
            samples = samples,
            distinctDays = distinctDays,
            sufficient = sufficient,
            insufficientReason = reason,
        )
    }

    /** Mean of the newest [lastN] readings (input newest-first), rounded; null if too few. */
    fun averageOfLastN(
        readingsNewestFirst: List<ContextMeasurement>,
        lastN: Int,
        roundingDecimals: Int,
    ): Double? {
        if (readingsNewestFirst.size < lastN) return null
        val selected = readingsNewestFirst.take(lastN)
        return round(selected.sumOf { it.value } / lastN, roundingDecimals)
    }

    fun round(value: Double, decimals: Int): Double =
        BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()
}
