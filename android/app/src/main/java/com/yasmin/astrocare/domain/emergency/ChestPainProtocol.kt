package com.yasmin.astrocare.domain.emergency

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Thrown when the bundled chest-pain protocol is malformed — fail loud, never run partial. */
class ChestPainProtocolException(val problems: List<String>) : IllegalArgumentException(
    "Invalid chest-pain protocol (${problems.size} problem(s)):\n- " + problems.joinToString("\n- "),
)

/**
 * Parsed `contracts/data/chest-pain-protocol.v1.json` (bundled at
 * `assets/contracts/chest-pain-protocol.v1.json`). Fully offline by contract:
 * no network client is reachable from this model or its loader.
 *
 * The outcome rule is applied by [outcomeFor] — implemented from the data
 * (`yesIsRedFlag`), never from hardcoded question ids.
 */
@Serializable
data class ChestPainProtocol(
    val schemaVersion: String,
    val protocolId: String,
    val protocolVersion: String,
    val locale: String,
    val fullyOffline: Boolean,
    val disclaimer: String,
    /** Shown BEFORE any question — the user never has to answer to see these. */
    val immediateSafetyInstructions: List<String>,
    val contactByEnvironment: Map<String, String>,
    val maxQuestions: Int,
    val questions: List<ProtocolQuestion>,
    val outcomeRule: String,
    val outcomes: Map<String, String>,
)

@Serializable
data class ProtocolQuestion(
    val id: String,
    val text: String,
    /** true for CP-Q1..CP-Q4 — 'yes'/'unsure' on any of these escalates. */
    val yesIsRedFlag: Boolean,
)

/** Contract triage answer enum (symptom-checkin.schema.json). */
enum class TriageAnswer(val wire: String) {
    YES("yes"),
    NO("no"),
    UNSURE("unsure");

    companion object {
        fun fromWire(value: String): TriageAnswer? = entries.firstOrNull { it.wire == value }
    }
}

/** Contract chestPainTriage.outcome enum. */
enum class TriageOutcome(val wire: String) {
    EMERGENCY_NOW("emergency_now"),
    URGENT_CONTACT("urgent_contact");

    companion object {
        fun fromWire(value: String): TriageOutcome? = entries.firstOrNull { it.wire == value }
    }
}

/** One answered question — contract triage `answers[]` item. */
data class AnsweredQuestion(val questionId: String, val answer: TriageAnswer)

/** Contract `chestPainTriage` object stored ON the symptom check-in (data, never an alert level). */
data class ChestPainTriage(
    val protocolId: String,
    val answers: List<AnsweredQuestion>,
    val outcome: TriageOutcome,
)

/** Pure loader — parses and validates the frozen protocol; malformed input aborts with every problem listed. */
object ChestPainProtocolLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
    }

    fun load(raw: String): ChestPainProtocol {
        val protocol = try {
            json.decodeFromString<ChestPainProtocol>(raw)
        } catch (e: SerializationException) {
            throw ChestPainProtocolException(listOf("not valid protocol JSON: ${e.message}"))
        }

        val problems = buildList {
            if (!protocol.fullyOffline) {
                add("fullyOffline must be true — the emergency flow runs with no network")
            }
            if (protocol.immediateSafetyInstructions.isEmpty()) {
                add("immediateSafetyInstructions must not be empty")
            }
            if (protocol.questions.isEmpty()) add("questions must not be empty")
            if (protocol.questions.size > protocol.maxQuestions) {
                add("${protocol.questions.size} questions exceed maxQuestions=${protocol.maxQuestions}")
            }
            val ids = protocol.questions.map { it.id }
            if (ids.size != ids.distinct().size) add("duplicate question ids")
            if (protocol.contactByEnvironment.isEmpty()) add("contactByEnvironment must not be empty")
            if (OUTCOME_EMERGENCY_NOW !in protocol.outcomes) {
                add("outcomes missing '$OUTCOME_EMERGENCY_NOW'")
            }
            if (OUTCOME_URGENT_CONTACT !in protocol.outcomes) {
                add("outcomes missing '$OUTCOME_URGENT_CONTACT'")
            }
        }
        if (problems.isNotEmpty()) throw ChestPainProtocolException(problems)
        return protocol
    }

    const val OUTCOME_EMERGENCY_NOW = "emergency_now"
    const val OUTCOME_URGENT_CONTACT = "urgent_contact"
}

/**
 * Contract `outcomeRule` (verbatim): "emergency_now if any of CP-Q1..CP-Q4 is
 * 'yes' or 'unsure'; otherwise urgent_contact."
 *
 * Implemented from the data: a question escalates when `yesIsRedFlag=true` and
 * it was answered 'yes' or 'unsure' (anything but 'no'). Unanswered red-flag
 * questions do NOT escalate — both outcomes keep the immediate safety
 * instructions visible (the caller must always show them).
 */
fun ChestPainProtocol.outcomeFor(answers: List<AnsweredQuestion>): TriageOutcome {
    val byId = questions.associateBy { it.id }
    val escalated = answers.any { answered ->
        byId[answered.questionId]?.yesIsRedFlag == true && answered.answer != TriageAnswer.NO
    }
    return if (escalated) TriageOutcome.EMERGENCY_NOW else TriageOutcome.URGENT_CONTACT
}
