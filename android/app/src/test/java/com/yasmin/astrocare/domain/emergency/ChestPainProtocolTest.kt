package com.yasmin.astrocare.domain.emergency

import com.yasmin.astrocare.bundledAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chest-pain protocol gate — parses the BUNDLED
 * `assets/contracts/chest-pain-protocol.v1.json` (fully offline) and proves
 * the contract outcome rule from the DATA (`yesIsRedFlag`): any red-flag
 * question answered yes/unsure -> emergency_now, otherwise urgent_contact.
 * The fifth (non-red-flag) question alone never escalates.
 */
class ChestPainProtocolTest {

    private val protocol: ChestPainProtocol by lazy {
        ChestPainProtocolLoader.load(
            bundledAsset("contracts/chest-pain-protocol.v1.json").readText(Charsets.UTF_8),
        )
    }

    private fun answersFor(overrides: Map<String, TriageAnswer>): List<AnsweredQuestion> =
        protocol.questions.map { q -> AnsweredQuestion(q.id, overrides[q.id] ?: TriageAnswer.NO) }

    @Test
    fun bundledProtocolIsFullyOfflineWithAtMostFiveQuestions() {
        assertTrue(protocol.fullyOffline)
        assertEquals("chest-pain-v1", protocol.protocolId)
        assertEquals("1.0.0", protocol.protocolVersion)
        assertTrue(protocol.immediateSafetyInstructions.isNotEmpty())
        assertTrue("must not exceed 5 questions", protocol.questions.size <= 5)
        assertEquals(5, protocol.maxQuestions)
        assertEquals(protocol.questions.size, protocol.questions.distinctBy { it.id }.size)
        assertTrue(protocol.contactByEnvironment.isNotEmpty())
        assertTrue(protocol.disclaimer.isNotBlank())
    }

    @Test
    fun allNoAnswersYieldUrgentContact() {
        val outcome = protocol.outcomeFor(answersFor(emptyMap()))
        assertEquals(TriageOutcome.URGENT_CONTACT, outcome)
    }

    @Test
    fun everyRedFlagYesEscalatesToEmergencyNow() {
        val redFlags = protocol.questions.filter { it.yesIsRedFlag }
        assertTrue("protocol must declare red-flag questions", redFlags.isNotEmpty())
        for (question in redFlags) {
            val outcome = protocol.outcomeFor(answersFor(mapOf(question.id to TriageAnswer.YES)))
            assertEquals("${question.id} yes must escalate", TriageOutcome.EMERGENCY_NOW, outcome)
        }
    }

    @Test
    fun everyRedFlagUnsureEscalatesToEmergencyNow() {
        for (question in protocol.questions.filter { it.yesIsRedFlag }) {
            val outcome = protocol.outcomeFor(answersFor(mapOf(question.id to TriageAnswer.UNSURE)))
            assertEquals("${question.id} unsure must escalate", TriageOutcome.EMERGENCY_NOW, outcome)
        }
    }

    @Test
    fun fifthQuestionYesAloneYieldsUrgentContact() {
        val fifth = protocol.questions.last()
        assertFalse(
            "CP-Q5 must not be a red flag (contract outcomeRule covers Q1..Q4 only)",
            fifth.yesIsRedFlag,
        )
        val outcome = protocol.outcomeFor(answersFor(mapOf(fifth.id to TriageAnswer.YES)))
        assertEquals(TriageOutcome.URGENT_CONTACT, outcome)
    }

    @Test
    fun mixedAnswersFollowRedFlagPresence() {
        val redFlag = protocol.questions.first { it.yesIsRedFlag }
        val nonRedFlag = protocol.questions.first { !it.yesIsRedFlag }
        // Red flag NO + non-red flag YES -> urgent_contact.
        assertEquals(
            TriageOutcome.URGENT_CONTACT,
            protocol.outcomeFor(
                answersFor(mapOf(redFlag.id to TriageAnswer.NO, nonRedFlag.id to TriageAnswer.YES)),
            ),
        )
        // Any red flag YES mixed with everything else -> emergency_now.
        assertEquals(
            TriageOutcome.EMERGENCY_NOW,
            protocol.outcomeFor(
                answersFor(mapOf(redFlag.id to TriageAnswer.YES, nonRedFlag.id to TriageAnswer.YES)),
            ),
        )
    }

    @Test
    fun bothOutcomesCarryTheImmediateSafetyInstructionsInTheContract() {
        // Contract: "Both outcomes keep the immediate safety instructions
        // visible" — the instructions must exist for the UI to show.
        assertTrue(protocol.immediateSafetyInstructions.size >= 4)
        assertTrue(protocol.outcomes.containsKey(ChestPainProtocolLoader.OUTCOME_EMERGENCY_NOW))
        assertTrue(protocol.outcomes.containsKey(ChestPainProtocolLoader.OUTCOME_URGENT_CONTACT))
    }

    @Test
    fun malformedProtocolFailsLoud() {
        val failure = runCatching { ChestPainProtocolLoader.load("{ not json") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is ChestPainProtocolException)

        val offlineFailure = runCatching {
            ChestPainProtocolLoader.load(
                """{"schemaVersion":"1.0","protocolId":"p","protocolVersion":"1.0.0","locale":"en","fullyOffline":false,"disclaimer":"d","immediateSafetyInstructions":["x"],"contactByEnvironment":{"iss":"c"},"maxQuestions":5,"questions":[{"id":"q","text":"t","yesIsRedFlag":true}],"outcomeRule":"r","outcomes":{"emergency_now":"a","urgent_contact":"b"}}""",
            )
        }
        assertTrue(offlineFailure.isFailure)
        val exception = offlineFailure.exceptionOrNull() as ChestPainProtocolException
        assertTrue(exception.problems.any { it.contains("fullyOffline") })
    }
}
