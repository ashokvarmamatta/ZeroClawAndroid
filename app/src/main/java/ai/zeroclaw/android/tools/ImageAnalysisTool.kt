package ai.zeroclaw.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ImageAnalysisTool — analyze images using vision-capable LLM models.
 *
 * Accepts a file path, content URI, or image URL. Encodes the image as
 * base64 and sends it to a vision model (GPT-4o, Gemini, Claude) for analysis.
 *
 * The tool uses the first available vision-capable API key from the key manager.
 */
class ImageAnalysisTool(private val context: Context) : Tool {

    override val name = "image_analysis"

    override val description = "Analyze an image and describe its contents. " +
            "Provide an image file path, content URI, or URL, plus a question/prompt about the image. " +
            "Uses a vision-capable AI model to analyze the image."

    override val parameters = listOf(
        ToolParam("source", "string", "File path, content:// URI, or URL to an image"),
        ToolParam("prompt", "string", "What to analyze or describe about the image", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val source = args["source"]?.trim()
            ?: return ToolResult(false, "", "Missing 'source' parameter")
        val prompt = args["prompt"]?.trim() ?: "Describe this image in detail."

        return withContext(Dispatchers.IO) {
            try {
                val imageBytes = loadImage(source)
                    ?: return@withContext ToolResult(false, "", "Could not load image from: $source")

                // Resize if too large (max 1024px on longest side to keep base64 reasonable)
                val resized = resizeIfNeeded(imageBytes, 1024)
                val base64 = Base64.encodeToString(resized, Base64.NO_WRAP)
                val mimeType = detectMimeType(imageBytes)

                // Try to call a vision model via the key manager
                val result = callVisionModel(base64, mimeType, prompt)
                    ?: return@withContext ToolResult(false, "", "No vision-capable API key available. Add an OpenAI (GPT-4o), Gemini, or Claude key.")

                ToolResult(true, result.take(MAX_RESULT_LENGTH))
            } catch (e: Exception) {
                ToolResult(false, "", "Image analysis failed: ${e.message}")
            }
        }
    }

    private fun loadImage(source: String): ByteArray? {
        return try {
            when {
                source.startsWith("http://") || source.startsWith("https://") -> {
                    val request = Request.Builder().url(source)
                        .header("User-Agent", "ZeroClaw/1.0")
                        .build()
                    client.newCall(request).execute().body?.bytes()
                }
                source.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(source))?.readBytes()
                }
                else -> {
                    val file = File(source)
                    if (file.exists()) file.readBytes() else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resizeIfNeeded(imageBytes: ByteArray, maxDim: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes

        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= maxDim) {
            // Re-encode as JPEG for consistent base64
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            return out.toByteArray()
        }

        val scale = maxDim.toFloat() / longestSide
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        bitmap.recycle()

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        return out.toByteArray()
    }

    private fun detectMimeType(bytes: ByteArray): String {
        return when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 4 && String(bytes.sliceArray(0..3)) == "GIF8" -> "image/gif"
            bytes.size >= 4 && String(bytes.sliceArray(0..3)) == "RIFF" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * Call a vision-capable model using OpenAI-compatible API format.
     * This uses the key manager to find available keys and tries them.
     */
    private fun callVisionModel(base64: String, mimeType: String, prompt: String): String? {
        // Get keys from SharedPreferences (same format as LlmKeyManager)
        val prefs = context.getSharedPreferences("zeroclaw_api_keys", Context.MODE_PRIVATE)
        val keysJson = prefs.getString("api_keys", null) ?: return null

        val gson = com.google.gson.GsonBuilder().serializeNulls().create()
        val type = object : com.google.gson.reflect.TypeToken<List<ai.zeroclaw.android.data.ApiKeyEntry>>() {}.type
        val keys: List<ai.zeroclaw.android.data.ApiKeyEntry> = gson.fromJson(keysJson, type) ?: return null

        // Try vision-capable providers in order
        for (entry in keys) {
            val result = when (entry.safeProvider.lowercase()) {
                "openai", "openrouter" -> callOpenAIVision(entry, base64, mimeType, prompt)
                "gemini" -> callGeminiVision(entry, base64, mimeType, prompt)
                "anthropic" -> callAnthropicVision(entry, base64, mimeType, prompt)
                else -> {
                    // Try OpenAI-compatible format for custom endpoints
                    if (entry.safeBaseUrl.isNotBlank()) callOpenAIVision(entry, base64, mimeType, prompt)
                    else null
                }
            }
            if (result != null) return result
        }
        return null
    }

    private fun callOpenAIVision(
        entry: ai.zeroclaw.android.data.ApiKeyEntry,
        base64: String, mimeType: String, prompt: String
    ): String? {
        return try {
            val baseUrl = entry.safeBaseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
            val model = entry.safePreferredModel.ifBlank { "gpt-4o" }

            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:$mimeType;base64,$base64")
                                })
                            })
                        })
                    })
                })
                put("max_tokens", 1000)
            }

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer ${entry.safeApiKey}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return null)
            json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } catch (_: Exception) {
            null
        }
    }

    private fun callGeminiVision(
        entry: ai.zeroclaw.android.data.ApiKeyEntry,
        base64: String, mimeType: String, prompt: String
    ): String? {
        return try {
            val model = entry.safePreferredModel.ifBlank { "gemini-2.0-flash" }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${entry.safeApiKey}"

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", mimeType)
                                    put("data", base64)
                                })
                            })
                        })
                    })
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return null)
            json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
        } catch (_: Exception) {
            null
        }
    }

    private fun callAnthropicVision(
        entry: ai.zeroclaw.android.data.ApiKeyEntry,
        base64: String, mimeType: String, prompt: String
    ): String? {
        return try {
            val model = entry.safePreferredModel.ifBlank { "claude-sonnet-4-20250514" }

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 1000)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", mimeType)
                                    put("data", base64)
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", entry.safeApiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return null)
            json.getJSONArray("content").getJSONObject(0).getString("text")
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_RESULT_LENGTH = 4000
    }
}
