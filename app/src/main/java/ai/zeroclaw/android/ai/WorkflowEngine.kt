package ai.zeroclaw.android.ai

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ai.zeroclaw.android.data.appDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkflowEngine — Phase 114: Multi-step automated pipelines.
 *
 * Allows users to define multi-step workflows where each step's output feeds
 * into the next step. Steps can be LLM prompts, tool calls, or conditions.
 * Inspired by OpenClaw's LLM Task tool and Lobster workflow system.
 *
 * Example workflow: "Research → Summarize → Translate → Email"
 */
class WorkflowEngine(private val context: Context) {

    data class WorkflowStep(
        val id: Int,
        val type: StepType,
        val prompt: String,         // LLM prompt or tool name
        val params: Map<String, String> = emptyMap(),
        val condition: String = ""  // optional: skip step if condition not met
    )

    enum class StepType { LLM_CALL, TOOL_CALL, CONDITION }

    data class Workflow(
        val id: String,
        val name: String,
        val description: String = "",
        val steps: List<WorkflowStep>,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("createdAt", createdAt)
            put("steps", JSONArray().apply {
                steps.forEach { step ->
                    put(JSONObject().apply {
                        put("id", step.id)
                        put("type", step.type.name)
                        put("prompt", step.prompt)
                        put("params", JSONObject(step.params))
                        put("condition", step.condition)
                    })
                }
            })
        }

        companion object {
            fun fromJson(json: JSONObject): Workflow {
                val stepsArr = json.optJSONArray("steps") ?: JSONArray()
                val steps = (0 until stepsArr.length()).map { i ->
                    val s = stepsArr.getJSONObject(i)
                    val paramsJson = s.optJSONObject("params") ?: JSONObject()
                    val params = mutableMapOf<String, String>()
                    paramsJson.keys().forEach { k -> params[k] = paramsJson.optString(k, "") }
                    WorkflowStep(
                        id = s.optInt("id", i),
                        type = runCatching { StepType.valueOf(s.optString("type", "LLM_CALL")) }.getOrDefault(StepType.LLM_CALL),
                        prompt = s.optString("prompt", ""),
                        params = params,
                        condition = s.optString("condition", "")
                    )
                }
                return Workflow(
                    id = json.optString("id", ""),
                    name = json.optString("name", ""),
                    description = json.optString("description", ""),
                    steps = steps,
                    createdAt = json.optLong("createdAt", System.currentTimeMillis())
                )
            }
        }
    }

    data class WorkflowResult(
        val success: Boolean,
        val stepResults: List<Pair<Int, String>>,  // stepId to result
        val finalOutput: String,
        val error: String = ""
    )

    companion object {
        private val KEY_WORKFLOWS = stringPreferencesKey("workflows")
        const val MAX_STEPS = 10

        // Built-in workflow templates
        val TEMPLATES = listOf(
            Workflow(
                id = "template_research_summarize",
                name = "Research & Summarize",
                description = "Search the web for a topic, then summarize the findings",
                steps = listOf(
                    WorkflowStep(0, StepType.LLM_CALL, "Search the web for: {input}. Return detailed findings."),
                    WorkflowStep(1, StepType.LLM_CALL, "Summarize the following research into 5 key bullet points:\n\n{prev_result}")
                )
            ),
            Workflow(
                id = "template_translate_email",
                name = "Translate & Email",
                description = "Translate text to a target language, then draft an email with it",
                steps = listOf(
                    WorkflowStep(0, StepType.LLM_CALL, "Translate the following to {language}: {input}"),
                    WorkflowStep(1, StepType.LLM_CALL, "Draft a professional email containing this translated text:\n\n{prev_result}\n\nMake it polished and ready to send.")
                )
            ),
            Workflow(
                id = "template_analyze_report",
                name = "Analyze & Report",
                description = "Analyze data/text and generate a structured report",
                steps = listOf(
                    WorkflowStep(0, StepType.LLM_CALL, "Analyze the following in detail, identifying key themes, patterns, and insights:\n\n{input}"),
                    WorkflowStep(1, StepType.LLM_CALL, "Based on this analysis, create a structured report with sections: Executive Summary, Key Findings, Recommendations:\n\n{prev_result}")
                )
            )
        )

        @Volatile private var INSTANCE: WorkflowEngine? = null
        fun getInstance(context: Context): WorkflowEngine {
            return INSTANCE ?: synchronized(this) {
                WorkflowEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Execute a workflow with the given input.
     * {input} in prompts is replaced with the user's original input.
     * {prev_result} is replaced with the previous step's output.
     */
    suspend fun execute(workflow: Workflow, input: String, chatId: String): WorkflowResult {
        val llmRouter = LlmRouter.getInstance(context)
        val stepResults = mutableListOf<Pair<Int, String>>()
        var prevResult = ""

        ZeroClawService.log("WORKFLOW: starting '${workflow.name}' — ${workflow.steps.size} steps")

        for (step in workflow.steps) {
            // Check condition
            if (step.condition.isNotBlank()) {
                val conditionMet = evaluateCondition(step.condition, prevResult, input)
                if (!conditionMet) {
                    ZeroClawService.log("WORKFLOW: step ${step.id} skipped (condition not met)")
                    stepResults.add(step.id to "[skipped]")
                    continue
                }
            }

            try {
                val result = when (step.type) {
                    StepType.LLM_CALL -> {
                        val prompt = step.prompt
                            .replace("{input}", input)
                            .replace("{prev_result}", prevResult)
                            .let { p ->
                                var result = p
                                step.params.forEach { (k, v) -> result = result.replace("{$k}", v) }
                                result
                            }

                        // Use a workflow-specific chat context
                        val workflowChatId = "workflow_${workflow.id}_${chatId}"
                        llmRouter.call(prompt, chatId = workflowChatId)
                    }
                    StepType.TOOL_CALL -> {
                        // Tool calls are handled via the LLM's tool calling mechanism
                        val toolPrompt = "Execute the ${step.prompt} tool with these parameters: ${step.params}. " +
                            "Context from previous step: $prevResult"
                        val workflowChatId = "workflow_${workflow.id}_${chatId}"
                        llmRouter.call(toolPrompt, chatId = workflowChatId)
                    }
                    StepType.CONDITION -> {
                        // Condition steps just pass through
                        prevResult
                    }
                }

                prevResult = result
                stepResults.add(step.id to result)
                ZeroClawService.log("WORKFLOW: step ${step.id} completed — ${result.length} chars")
            } catch (e: Exception) {
                ZeroClawService.log("WORKFLOW: step ${step.id} failed — ${e.message}")
                return WorkflowResult(
                    success = false,
                    stepResults = stepResults,
                    finalOutput = "",
                    error = "Step ${step.id} failed: ${e.message}"
                )
            }
        }

        ZeroClawService.log("WORKFLOW: '${workflow.name}' completed successfully")
        return WorkflowResult(
            success = true,
            stepResults = stepResults,
            finalOutput = prevResult
        )
    }

    /**
     * Simple condition evaluator. Supports:
     * - "contains:keyword" — check if prev_result contains keyword
     * - "not_empty" — check if prev_result is not blank
     * - "length>N" — check if prev_result length exceeds N
     */
    private fun evaluateCondition(condition: String, prevResult: String, input: String): Boolean {
        return when {
            condition.startsWith("contains:") -> {
                val keyword = condition.substringAfter("contains:").trim()
                prevResult.contains(keyword, ignoreCase = true)
            }
            condition == "not_empty" -> prevResult.isNotBlank()
            condition.startsWith("length>") -> {
                val threshold = condition.substringAfter("length>").trim().toIntOrNull() ?: 0
                prevResult.length > threshold
            }
            else -> true // unknown conditions pass by default
        }
    }

    /**
     * Save a custom workflow.
     */
    suspend fun saveWorkflow(workflow: Workflow) {
        context.appDataStore.edit { prefs ->
            val existing = runCatching { JSONArray(prefs[KEY_WORKFLOWS] ?: "[]") }.getOrDefault(JSONArray())
            val updated = JSONArray()
            var replaced = false
            for (i in 0 until existing.length()) {
                val w = existing.getJSONObject(i)
                if (w.optString("id") == workflow.id) {
                    updated.put(workflow.toJson())
                    replaced = true
                } else {
                    updated.put(w)
                }
            }
            if (!replaced) updated.put(workflow.toJson())
            prefs[KEY_WORKFLOWS] = updated.toString()
        }
    }

    /**
     * Get all workflows (templates + custom).
     */
    suspend fun getAllWorkflows(): List<Workflow> {
        val prefs = context.appDataStore.data.first()
        val customJson = prefs[KEY_WORKFLOWS] ?: "[]"
        val custom = runCatching {
            val arr = JSONArray(customJson)
            (0 until arr.length()).map { Workflow.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return TEMPLATES + custom
    }

    /**
     * Delete a custom workflow by ID.
     */
    suspend fun deleteWorkflow(workflowId: String) {
        context.appDataStore.edit { prefs ->
            val existing = runCatching { JSONArray(prefs[KEY_WORKFLOWS] ?: "[]") }.getOrDefault(JSONArray())
            val updated = JSONArray()
            for (i in 0 until existing.length()) {
                val w = existing.getJSONObject(i)
                if (w.optString("id") != workflowId) updated.put(w)
            }
            prefs[KEY_WORKFLOWS] = updated.toString()
        }
    }
}
