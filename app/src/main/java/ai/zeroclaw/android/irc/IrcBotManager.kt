package ai.zeroclaw.android.irc

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * IrcBotManager — IRC protocol bot via raw TCP.
 *
 * Connects to an IRC server, joins channels, responds to messages.
 * Config format: "irc.server.net:6667|nickname|#channel1,#channel2"
 */
class IrcBotManager(private val context: Context) {

    private val llmRouter = LlmRouter.getInstance(context)
    private var running = false
    private var socket: Socket? = null
    private var writer: PrintWriter? = null

    /**
     * Start IRC bot. Config: "server:port|nickname|#channel1,#channel2"
     */
    suspend fun start(config: String) = withContext(Dispatchers.IO) {
        val parts = config.split("|")
        if (parts.size < 3) {
            ZeroClawService.log("IRC: invalid config. Use 'server:port|nickname|#channel1,#channel2'")
            return@withContext
        }

        val serverParts = parts[0].split(":")
        val server = serverParts[0].trim()
        val port = serverParts.getOrNull(1)?.trim()?.toIntOrNull() ?: 6667
        val nickname = parts[1].trim()
        val channels = parts[2].split(",").map { it.trim() }

        running = true
        ZeroClawService.log("IRC: connecting to $server:$port as $nickname...")

        while (running) {
            try {
                socket = Socket(server, port)
                val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                writer = PrintWriter(socket!!.getOutputStream(), true)

                // Register
                send("NICK $nickname")
                send("USER $nickname 0 * :ZeroClaw AI Bot")

                var registered = false
                var line: String? = null

                while (running && reader.readLine().also { line = it } != null) {
                    val msg = line ?: continue

                    // Handle PING
                    if (msg.startsWith("PING")) {
                        send("PONG ${msg.substringAfter("PING ")}")
                        continue
                    }

                    // On welcome (001), join channels
                    if (msg.contains(" 001 ") && !registered) {
                        registered = true
                        ZeroClawService.log("IRC: registered as $nickname")
                        for (ch in channels) {
                            send("JOIN $ch")
                            ZeroClawService.log("IRC: joining $ch")
                        }
                    }

                    // Handle PRIVMSG
                    if (msg.contains("PRIVMSG")) {
                        handlePrivMsg(msg, nickname, channels)
                    }
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                ZeroClawService.log("IRC: connection error — ${e.message}")
            }

            socket?.close()
            if (running) {
                ZeroClawService.log("IRC: reconnecting in 10s...")
                delay(10_000)
            }
        }
    }

    private fun handlePrivMsg(raw: String, myNick: String, channels: List<String>) {
        // Format: :nick!user@host PRIVMSG #channel :message
        val match = Regex("^:([^!]+)!\\S+ PRIVMSG (\\S+) :(.+)$").find(raw) ?: return
        val sender = match.groupValues[1]
        val target = match.groupValues[2]
        val message = match.groupValues[3].trim()

        if (message.isBlank()) return

        // For channel messages, only respond if mentioned
        val isDirectMessage = !target.startsWith("#")
        val isMentioned = message.startsWith("$myNick:", ignoreCase = true) ||
                message.startsWith("$myNick,", ignoreCase = true) ||
                message.contains("@$myNick", ignoreCase = true)

        if (!isDirectMessage && !isMentioned) return

        val cleanMessage = if (isMentioned) {
            message.replaceFirst(Regex("^$myNick[,:@]?\\s*", RegexOption.IGNORE_CASE), "").trim()
        } else {
            message
        }

        if (cleanMessage.isBlank()) return

        ZeroClawService.log("IRC @$sender ($target): $cleanMessage")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reply = llmRouter.call(cleanMessage, chatId = "irc_$sender")
                val replyTarget = if (isDirectMessage) sender else target

                // IRC has ~512 char line limit, chunk replies
                val lines = reply.split("\n")
                for (line in lines.take(10)) {
                    if (line.isNotBlank()) {
                        val chunks = line.chunked(400)
                        for (chunk in chunks) {
                            send("PRIVMSG $replyTarget :$chunk")
                            delay(500) // Rate limit
                        }
                    }
                }
                ZeroClawService.log("IRC: reply sent to $replyTarget")
            } catch (e: Exception) {
                ZeroClawService.log("IRC: reply error — ${e.message}")
            }
        }
    }

    /** Public API for proactive messaging from agents/crons. Target = #channel or nickname. */
    fun sendProactiveMessage(target: String, text: String) {
        val chunks = text.chunked(400)
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
