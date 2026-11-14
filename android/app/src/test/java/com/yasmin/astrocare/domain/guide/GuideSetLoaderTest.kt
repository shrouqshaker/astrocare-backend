package com.yasmin.astrocare.domain.guide

import com.yasmin.astrocare.bundledAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guide set gate — the frozen guides.v1.json parses fail-loud, covers all
 * five metrics, and every `deviceIllustration.assetName` maps to a bundled
 * PNG that actually exists on disk (illustrations are NEVER downloaded).
 */
class GuideSetLoaderTest {

    private val guideSet: GuideSet by lazy {
        GuideSetLoader.load(bundledAsset("contracts/guides.v1.json").readText(Charsets.UTF_8))
    }

    @Test
    fun fiveGuidesCoverFiveMetricsUniquely() {
        assertEquals("1.0", guideSet.schemaVersion)
        assertEquals("1.0.0", guideSet.guideSetVersion)
        assertEquals(5, guideSet.guides.size)
        assertEquals(5, guideSet.guides.map { it.metric }.distinct().size)
        assertEquals(5, guideSet.guides.map { it.id }.distinct().size)
        assertEquals(
            setOf("heart_rate", "blood_pressure", "spo2", "temperature", "sleep"),
            guideSet.guides.map { it.metric }.toSet(),
        )
    }

    @Test
    fun everyIllustrationAssetExistsOnDisk() {
        for (guide in guideSet.guides) {
            val assetName = guide.deviceIllustration.assetName
            assertTrue("assetName must not be blank", assetName.isNotBlank())
            val file = bundledAsset("$assetName.png")
            assertTrue("$assetName.png missing from assets", file.isFile)
            assertTrue("$assetName.png is empty", file.length() > 0)
            assertTrue(
                "$assetName altText must not be blank",
                guide.deviceIllustration.altText.isNotBlank(),
            )
        }
    }

    @Test
    fun everyGuideHasHowToStepsReferenceRangeAndDisclaimer() {
        for (guide in guideSet.guides) {
            assertTrue("${guide.id}: steps empty", guide.howToMeasure.steps.isNotEmpty())
            assertTrue("${guide.id}: whyItMatters blank", guide.whyItMatters.isNotBlank())
            assertTrue("${guide.id}: referenceRange rows empty", guide.referenceRange.rows.isNotEmpty())
            assertTrue("${guide.id}: disclaimer blank", guide.disclaimer.isNotBlank())
            for (row in guide.referenceRange.rows) {
                assertTrue("${guide.id}: row unit blank", row.unit.isNotBlank())
            }
            assertTrue("${guide.id}: warning guidance empty", guide.whatToDoIfAbnormal.warning.isNotEmpty())
            assertTrue("${guide.id}: critical guidance empty", guide.whatToDoIfAbnormal.critical.isNotEmpty())
            assertTrue("${guide.id}: sources empty", guide.sources.isNotEmpty())
        }
    }

    @Test
    fun forMetricReturnsMatchingGuideOrNull() {
        assertEquals("guide-heart_rate", guideSet.forMetric("heart_rate")?.id)
        assertEquals(null, guideSet.forMetric("unknown_metric"))
    }

    @Test
    fun malformedGuideSetFailsLoud() {
        val failure = runCatching { GuideSetLoader.load("{ nope") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is GuideSetException)

        val emptyFailure = runCatching {
            GuideSetLoader.load("""{"schemaVersion":"1.0","guideSetVersion":"1.0.0","locale":"en","generatedFromRuleSetVersion":"1.0.0","guides":[]}""")
        }
        assertTrue(emptyFailure.isFailure)
        val exception = emptyFailure.exceptionOrNull() as GuideSetException
        assertTrue(exception.problems.any { it.contains("must not be empty") })
    }
}
