package ai.zeroclaw.android.whatsapp

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.data.SettingsData
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Twilio WhatsApp Manager
 * - Starts a lightweight HTTP server to receive Twilio webhooks
 * - Processes incoming WhatsApp messages via LlmRouter (with failover)
 * - Replies via Twilio REST API
 *
 * Setup: See Settings → Setup Guide → WhatsApp tab
 */
class TwilioWhatsAppManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val llmRouter = LlmRouter.getInstance(context)
    private var serverSocket: ServerSocket? = null
    private var running = false

    suspend fun startWebhookServer(settings: SettingsData, port: Int) {
        running = true
        withContext(Dispatchers.IO) {
            serverSocket = ServerSocket(port)
            ZeroClawService.log("WhatsApp webhook listening on port $port")
            while (running) {
                try {
                    val socket = serverSocket!!.accept()
                    launch { handleRequest(socket, settings) }
                } catch (e: Exception) {
                    if (running) ZeroClawService.log("Webhook server error: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleRequest(socket: Socket, settings: SettingsData) {
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                val requestLine = reader.readLine() ?: return@withContext
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                var contentLength = 0
                while (line != null && line.isNotEmpty()) {
                    val parts = line.split(": ", limit = 2)
                    if (parts.size == 2) {
                        headers[parts[0].lowercase()] = parts[1]
                        if (parts[0].lowercase() == "content-length")
                            contentLength = parts[1].toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }
                val bodyChars = CharArray(contentLength)
                reader.read(bodyChars, 0, contentLength)
                val body = String(bodyChars)

                if (requestLine.startsWith("POST") && requestLine.contains("/whatsapp")) {
                    processWhatsAppWebhook(body, settings, writer)
                } else {
                    sendHttpResponse(writer, 200, "OK", "ZeroClaw WhatsApp webhook ready")
                }
            } catch (e: Exception) {
                ZeroClawService.log("Webhook handle error: ${e.message}")
            } finally {
                socket.close()
            }
        }
    }

    private suspend fun processWhatsAppWebhook(body: String, settings: SettingsData, writer: PrintWriter) {
        val params = parseFormData(body)
        val from = params["From"] ?: return
        val messageBody = params["Body"] ?: return
        ZeroClawService.log("WhatsApp from $from: $messageBody")

        // LlmRouter handles all provider logic + failover
        val reply = llmRouter.call(messageBody)

        sendTwilioMessage(settings, from, reply)
        sendHttpResponse(writer, 200, "OK", "<Response></Response>")
        ZeroClawService.log("WhatsApp reply sent to $from")
    }

    private fun parseFormData(body: String): Map<String, String> {
        return body.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key   = if (parts.isNotEmpty()) java.net.URLDecoder.decode(parts[0], "UTF-8") else ""
            val value = if (parts.size > 1)     java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }

    /**
     * Public API for proactive messaging from agents/crons.
     * chatId = WhatsApp number in Twilio format (e.g. "whatsapp:+1234567890").
     */
    suspend fun sendProactiveMessage(chatId: String, text: String) {
        val settings = ai.zeroclaw.android.data.AppSettings(context).getAll()
        if (settings.twilioSid.isBlank()) {
            ZeroClawService.log("WhatsApp: Twilio not configured for proactive send")
            return
        }
        val to = if (chatId.startsWith("whatsapp:")) chatId else "whatsapp:$chatId"
        sendTwilioMessage(settings, to, text)
    }

    private suspend fun sendTwilioMessage(settings: SettingsData, to: String, body: String) {
        val credentials = "${settings.twilioSid}:${settings.twilioToken}"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
        val formBody = FormBody.Builder()
            .add("To", to).add("From", settings.twilioFrom).add("Body", body).build()
        val request = Request.Builder()
            .url("https://api.twilio.com/2010-04-01/Accounts/${settings.twilioSid}/Messages.json")
            .addHeader("Authorization", "Basic $encoded")
            .post(formBody).build()
        withContext(Dispatchers.IO) {
            runCatching { httpClient.newCall(request).execute().close() }
        }
    }

    private fun sendHttpResponse(writer: PrintWriter, code: Int, status: String, body: String) {
        writer.println("HTTP/1.1 $code $status")
        writer.println("Content-Type: text/xml")
        writer.println("Content-Length: ${body.length}")
        writer.println("Connection: close")
        writer.println()
        writer.println(body)
        writer.flush()
    }

    fun stop() {
        running = false
        serverSocket?.close()
    }
}
