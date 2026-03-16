package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * EmailTool — send emails from chat.
 *
 * Supports two modes:
 * 1. SendGrid API (recommended) — just needs an API key
 * 2. Mailgun API — needs API key + domain
 *
 * The LLM composes the email and the tool sends it.
 * This is a send-only tool — receiving requires webhook setup.
 */
class EmailTool : Tool {

    override val name = "email"

    override val description = "Send emails from chat. " +
            "Provide recipient, subject, body, and either a SendGrid or Mailgun API key. " +
            "Actions: 'send' (send an email), 'draft' (preview without sending)."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: send, draft. Default: draft.", required = false),
        ToolParam("to", "string", "Recipient email address"),
        ToolParam("subject", "string", "Email subject line"),
        ToolParam("body", "string", "Email body (plain text)"),
        ToolParam("from", "string", "Sender email address", required = false),
        ToolParam("provider", "string", "Email provider: 'sendgrid' or 'mailgun'. Default: sendgrid.", required = false),
        ToolParam("api_key", "string", "SendGrid API key (SG.xxx) or Mailgun API key"),
        ToolParam("domain", "string", "Mailgun domain (required for Mailgun provider)", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase() ?: "draft"
        val to = args["to"]?.trim()
            ?: return ToolResult(false, "", "Missing 'to' parameter (recipient email).")
        val subject = args["subject"]?.trim()
            ?: return ToolResult(false, "", "Missing 'subject' parameter.")
        val body = args["body"]?.trim()
            ?: return ToolResult(false, "", "Missing 'body' parameter.")
        val apiKey = args["api_key"]?.trim()
            ?: return ToolResult(false, "", "Missing 'api_key' parameter.")

        // Validate email format
        if (!to.contains("@") || !to.contains(".")) {
            return ToolResult(false, "", "Invalid email address: $to")
        }

        if (action == "draft") {
            return ToolResult(true, buildDraft(to, subject, body, args))
        }

        val provider = args["provider"]?.trim()?.lowercase() ?: "sendgrid"

        return withContext(Dispatchers.IO) {
            when (provider) {
                "sendgrid" -> sendViaSendGrid(to, subject, body, apiKey, args)
                "mailgun" -> sendViaMailgun(to, subject, body, apiKey, args)
                else -> ToolResult(false, "", "Unknown provider '$provider'. Use 'sendgrid' or 'mailgun'.")
            }
        }
    }

    private fun buildDraft(to: String, subject: String, body: String, args: Map<String, String>): String {
        val from = args["from"]?.trim() ?: "(default sender)"
        return """
            |📧 Email Draft (not sent)
            |═══════════════════════
            |From: $from
            |To: $to
            |Subject: $subject
            |
            |$body
            |
            |---
            |To send this email, use action: 'send' with an API key.
        """.trimMargin()
    }

    private fun sendViaSendGrid(
        to: String, subject: String, body: String,
        apiKey: String, args: Map<String, String>
    ): ToolResult {
        val from = args["from"]?.trim() ?: "zeroclaw@noreply.com"

        return try {
            val json = JSONObject().apply {
                put("personalizations", JSONArray().apply {
                    put(JSONObject().apply {
                        put("to", JSONArray().apply {
                            put(JSONObject().put("email", to))
                        })
                    })
                })
                put("from", JSONObject().put("email", from))
                put("subject", subject)
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text/plain")
                        put("value", body)
                    })
                })
            }

            val request = Request.Builder()
                .url("https://api.sendgrid.com/v3/mail/send")
                .header("Authorization", "Bearer $apiKey")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (response.code in 200..299) {
                ToolResult(true, "✓ Email sent successfully via SendGrid!\nTo: $to\nSubject: $subject")
            } else {
                val errBody = response.body?.string() ?: ""
                ToolResult(false, "", "SendGrid error (${response.code}): ${errBody.take(300)}")
            }
        } catch (e: Exception) {
            ToolResult(false, "", "SendGrid send failed: ${e.message}")
        }
    }

    private fun sendViaMailgun(
        to: String, subject: String, body: String,
        apiKey: String, args: Map<String, String>
    ): ToolResult {
        val domain = args["domain"]?.trim()
            ?: return ToolResult(false, "", "Missing 'domain' parameter for Mailgun.")
        val from = args["from"]?.trim() ?: "ZeroClaw <zeroclaw@$domain>"

        return try {
            // Mailgun uses form-encoded POST, not JSON
            val formBody = okhttp3.FormBody.Builder()
                .add("from", from)
                .add("to", to)
                .add("subject", subject)
                .add("text", body)
                .build()

            val credentials = okhttp3.Credentials.basic("api", apiKey)
            val request = Request.Builder()
                .url("https://api.mailgun.net/v3/$domain/messages")
                .header("Authorization", credentials)
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                ToolResult(true, "✓ Email sent successfully via Mailgun!\nTo: $to\nSubject: $subject")
            } else {
                ToolResult(false, "", "Mailgun error (${response.code}): ${respBody.take(300)}")
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Mailgun send failed: ${e.message}")
        }
    }
}
