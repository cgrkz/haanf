package app.haanf.ai

import app.haanf.data.FactKind
import app.haanf.training.Zone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Schemas shared by the AI features, and tolerant reads of what comes back. */
object CoachTools {
    val REMEMBER_FACT by lazy {
        ToolSpec(
            name = "remember_fact",
            description = "Propose saving something lasting about the athlete. They confirm it first.",
            properties = buildJsonObject {
                put("kind", choice("What sort of fact.", FactKind.entries.map { it.name }))
                put("fact", string("A short statement, such as Left knee sore since October."))
            },
        )
    }

    val TARGETS = mapOf(
        "recovery" to Zone.RECOVERY,
        "easy" to Zone.EASY,
        "hard" to Zone.HARD,
        "very_hard" to Zone.VERY_HARD,
    )

    fun string(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun choice(description: String, options: Collection<String>): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        putJsonArray("enum") { options.forEach { add(JsonPrimitive(it)) } }
    }

    val PROPOSE_WORKOUT = ToolSpec(
        name = "propose_workout",
        description = "Propose today's workout. A separate safety check approves it, and tells you " +
            "what to change if it does not.",
        properties = buildJsonObject {
            put("title", string("A short title, such as Easy 30 minutes."))
            put(
                "reason",
                string(
                    "One or two sentences to the athlete on why this workout today. Name what decided " +
                        "it, such as their sleep, their last hard day or the time they have.",
                ),
            )
            putJsonObject("steps") {
                put("type", "array")
                put("description", "The steps in order.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("label", string("A short spoken label, such as Warm up."))
                        putJsonObject("minutes") {
                            put("type", "number")
                            put("description", "Length in minutes.")
                        }
                        put("target", choice("Target heart rate zone.", TARGETS.keys))
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("label"))
                        add(JsonPrimitive("minutes"))
                        add(JsonPrimitive("target"))
                    }
                    put("additionalProperties", false)
                }
            }
        },
    )

    val SUGGEST_BENCHMARK = ToolSpec(
        name = "suggest_benchmark",
        description = "Suggest repeating the fitness benchmark instead of a workout. The test itself is " +
            "fixed: you only choose when.",
        properties = buildJsonObject {
            put("reason", string("One sentence to the athlete on why a benchmark is due."))
        },
    )

    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

    fun readWorkout(input: JsonObject): ProposedWorkout? {
        val title = input.text("title") ?: return null
        val steps = (input["steps"] as? JsonArray) ?: return null
        val parsed = steps.map { readStep(it) ?: return null }
        return ProposedWorkout(title, input.text("reason").orEmpty(), parsed)
    }

    private fun readStep(element: JsonElement): ProposedStep? {
        val step = element as? JsonObject ?: return null
        val minutes = (step["minutes"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val target = TARGETS[step.text("target")] ?: return null
        return ProposedStep(step.text("label") ?: return null, minutes, target)
    }
}
