package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.data.LlmKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * ImageGenTool — generate images from text prompts.
 *
 * Primary: Pollinations.ai (100% free, no key, no signup).
 * Optional: DALL-E 3 via OpenAI API (if user has OpenAI key).
 *
 * Returns a URL to the generated image.
 * Inspired by OpenClaw's image generation skill.
 */
class ImageGenTool(private val context: Context) : Tool {

    override val name = "image_gen"

    override val description = "Generate an image from a text description/prompt. " +
            "Returns a URL to the generated image. Free via Pollinations.ai (no key needed), " +
            "or DALL-E 3 if an OpenAI key is configured. Specify provider with 'provider' param."

    override val parameters = listOf(
        ToolParam("prompt", "string", "Detailed description of the image to generate"),
        ToolParam("provider", "string", "One of: 'pollinations' (free, default), 'dalle' (needs OpenAI key).", required = false),
        ToolParam("size", "string", "Image size: 'square' (1024x1024), 'wide' (1792x1024), 'tall' (1024x1792). Default: square.", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // image gen can be slow
        .followRedirects(true)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val prompt = args["prompt"]?.trim()
            ?: return ToolResult(false, "", "Missing 'prompt' parameter")

        if (prompt.isBlank()) return ToolResult(false, "", "Empty prompt")

        val provider = args["provider"]?.trim()?.lowercase() ?: "pollinations"
        val size = args["size"]?.trim()?.lowercase() ?: "square"

        return withContext(Dispatchers.IO) {
            try {
                when (provider) {
                    "dalle", "dall-e", "openai" -> generateDalle(prompt, size)
                    else -> generatePollinations(prompt, size)
                }
            } catch (e: Exception) {
                ToolResult(false, "", "Image generation failed: ${e.message}")
            }
        }
    }

    /**
     * Pollinations.ai — completely free, no API key, no signup.
     * Returns a direct image URL.
     */
    private fun generatePollinations(prompt: String, size: String): ToolResult {
        val (width, height) = when (size) {
            "wide" -> 1792 to 1024
            "tall" -> 1024 to 1792
            else -> 1024 to 1024
        }

        val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
        val imageUrl = "https://image.pollinations.ai/prompt/$encodedPrompt?width=$width&height=$height&nologo=true"

        // Verify the URL works by sending a HEAD request
        val checkRequest = Request.Builder()
            .url(imageUrl)
            .head()
            .header("User-Agent", "ZeroClaw-Android/1.0")
            .build()

        val response = client.newCall(checkRequest).execute()
        if (!response.isSuccessful && response.code != 302 && response.code != 301) {
            return ToolResult(false, "", "Pollinations.ai returned ${response.code}")
        }

        val sb = StringBuilder("Image Generated (Pollinations.ai)\n")
        sb.appendLine("═══════════════════════════════════")
        sb.appendLine()
        sb.appendLine("Prompt: $prompt")
        sb.appendLine("Size: ${width}x${height}")
        sb.appendLine()
        sb.appendLine("Image URL: $imageUrl")
        sb.appendLine()
        sb.appendLine("The image is ready! Click the URL above to view it.")
        sb.appendLine("Note: Pollinations.ai generates a new image each time the URL is loaded.")

        return ToolResult(true, sb.toString())
    }

    /**
     * DALL-E 3 via OpenAI API — needs an OpenAI API key.
     */
    private fun generateDalle(prompt: String, size: String): ToolResult {
        val keyManager = LlmKeyManager.getInstance(context)
        val keys = keyManager.loadKeys().filter { it.enabled }
        val openaiKey = keys.firstOrNull {
            it.safeProvider == "openai" || it.safeProvider == "openrouter"
        }

        if (openaiKey == null) {
            // Fallback to Pollinations
            return generatePollinations(prompt, size)
        }

        val dalleSize = when (size) {
            "wide" -> "1792x1024"
            "tall" -> "1024x1792"
            else -> "1024x1024"
        }

        val baseUrl = openaiKey.safeBaseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        val body = JSONObject().apply {
            put("model", "dall-e-3")
            put("prompt", prompt.take(4000))
            put("n", 1)
            put("size", dalleSize)
            put("quality", "standard")
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/images/generations")
            .addHeader("Authorization", "Bearer ${openaiKey.safeApiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return ToolResult(false, "", "Empty DALL-E response")

        if (!response.isSuccessful) {
            val error = try {
                JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
            } catch (_: Exception) { "HTTP ${response.code}" }
            // Fallback to Pollinations on DALL-E failure
            return generatePollinations(prompt, size)
        }

        val json = JSONObject(responseBody)
        val data = json.optJSONArray("data")?.optJSONObject(0)
        val imageUrl = data?.optString("url", "") ?: ""
        val revisedPrompt = data?.optString("revised_prompt", prompt) ?: prompt

        if (imageUrl.isBlank()) {
            return generatePollinations(prompt, size)
        }

        val sb = StringBuilder("Image Generated (DALL-E 3)\n")
        sb.appendLine("══════════════════════════")
        sb.appendLine()
        sb.appendLine("Prompt: $prompt")
        if (revisedPrompt != prompt) {
            sb.appendLine("Revised prompt: $revisedPrompt")
        }
        sb.appendLine("Size: $dalleSize")
        sb.appendLine()
        sb.appendLine("Image URL: $imageUrl")
        sb.appendLine()
        sb.appendLine("Note: DALL-E URLs expire after ~1 hour. Save the image if you need it.")

        return ToolResult(true, sb.toString())
    }
}
