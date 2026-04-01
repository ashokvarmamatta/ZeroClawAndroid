package ai.zeroclaw.android.twitch

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * TwitchBotManager — Twitch chat bot via IRC (TMI).
 *
 * Twitch chat uses IRC protocol over SSL on irc.chat.twitch.tv:6697.
 * Config format: "oauth:token|botname|#channel1,#channel2"
 */
class TwitchBotManager(private val context: Context) {

    private val llmRouter = LlmRouter.getInstance(context)
    private var running = false
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var botName = ""

    /**
     * Start Twitch bot. Config: "oauth:token|botname|#channel1,#channel2"
     */
    suspend fun start(config: String) = withContext(Dispatchers.IO) {
        val parts = config.split("|")
        if (parts.size < 3) {
            ZeroClawService.log("Twitch: invalid config. Use 'oauth:token|botname|#channel1,#channel2'")
            return@withContext
        }

        val oauthToken = parts[0].trim()
        botName = parts[1].trim().lowercase()
        val channels = parts[2].split(",").map { it.trim().lowercase() }

        running = true

        while (running) {
            try {
                ZeroClawService.log("Twitch: connecting to TMI...")
                socket = javax.net.ssl.SSLSocketFactory.getDefault()
                    .createSocket("irc.chat.twitch.tv", 6697)
                val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                writer = PrintWriter(socket!!.getOutputStream(), true)

                // Authenticate
                send("PASS $oauthToken")
                send("NICK $botName")

                // Request tags for display names
                send("CAP REQ :twitch.tv/tags twitch.tv/commands")

                var authenticated = false
                var line: String? = null

                while (running && reader.readLine().also { line = it } != null) {
                    val msg = line ?: continue

                    // Handle PING
                    if (msg.startsWith("PING")) {
                        send("PONG ${msg.substringAfter("PING ")}")
                        continue
                    }

                    // On successful auth (376 = end of MOTD)
                    if ((msg.contains(" 376 ") || msg.contains(" 001 ")) && !authenticated) {
                        authenticated = true
                        ZeroClawService.log("Twitch: authenticated as $botName")
                        for (ch in channels) {
                            val channel = if (ch.startsWith("#")) ch else "#$ch"
                            send("JOIN $channel")
                            ZeroClawService.log("Twitch: joining $channel")
                        }
                    }

                    // Handle PRIVMSG (chat messages)
                    if (msg.contains("PRIVMSG")) {
                        handleChatMessage(msg)
                    }

                    // Handle auth failure
                    if (msg.contains("Login authentication failed")) {
                        ZeroClawService.log("Twitch: authentication failed. Check OAuth token.")
                        running = false
                        break
                    }
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                ZeroClawService.log("Twitch: connection error — ${e.message}")
            }

            socket?.close()
            if (running) {
                ZeroClawService.log("Twitch: reconnecting in 10s...")
                delay(10_000)
            }
        }
    }

    private fun handleChatMessage(raw: String) {
        // Twitch IRC format: @tags :user!user@user.tmi.twitch.tv PRIVMSG #channel :message
        val privmsgIdx = raw.indexOf("PRIVMSG")
        if (privmsgIdx < 0) return

        val prefix = raw.substring(0, privmsgIdx)
        val rest = raw.substring(privmsgIdx)

        // Extract sender
        val senderMatch = Regex(":([^!]+)!").find(prefix)
        val sender = senderMatch?.groupValues?.get(1)?.lowercase() ?: return

        // Ignore own messages
        if (sender == botName) return

        // Extract display name from tags
        val displayName = Regex("display-name=([^;]*)").find(prefix)?.groupValues?.get(1) ?: sender

        // Extract channel and message
        val channelMatch = Regex("PRIVMSG (#\\S+) :(.+)").find(rest) ?: return
        val channel = channelMatch.groupValues[1]
        val message = channelMatch.groupValues[2].trim()

        if (message.isBlank()) return

        // Only respond when mentioned or in DM
        val isMentioned = message.startsWith("@$botName", ignoreCase = true) ||
                message.startsWith("$botName", ignoreCase = true) ||
                message.contains("@$botName", ignoreCase = true)

        if (!isMentioned) return

        val cleanMessage = message
            .replace(Regex("@?$botName[,:]?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleanMessage.isBlank()) return

        ZeroClawService.log("Twitch @$displayName ($channel): $cleanMessage")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reply = llmRouter.call(cleanMessage, chatId = "twitch_$sender")
                // Twitch 500 char limit per message
                val chunks = reply.replace("\n", " ").chunked(450)
                for (chunk in chunks.take(3)) {
                    send("PRIVMSG $channel :$chunk")
                    delay(1500) // Twitch rate limiting
                }
                ZeroClawService.log("Twitch: reply sent to $channel")
            } catch (e: Exception) {
                ZeroClawService.log("Twitch: reply error — ${e.message}")
            }
        }
    }

    /** Public API for proactive messaging from agents/crons. Target = #channel. */
    fun sendProactiveMessage(channel: String, text: String) {
        val target = if (channel.startsWith("#")) channel else "#$channel"
        val chunks = text.chunked(450)
        for (chunk in chunks) {
            send("PRIVMSG $target :$chunk")
        }
    }

    private fun send(message: String) {
        writer?.println(message)
    }

    fun stop() {
        running = false
        runCatching { send("QUIT :ZeroClaw shutting down") }
        runCatching { socket?.close() }
        socket = null
        writer = null
    }
}
