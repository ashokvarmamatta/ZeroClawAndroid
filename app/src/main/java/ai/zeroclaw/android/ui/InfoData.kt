package ai.zeroclaw.android.ui

import androidx.compose.ui.graphics.Color

// ─── Data models ─────────────────────────────────────────────────────────────

data class GuideStep(
    val number: Int,
    val icon: String,          // emoji
    val title: String,
    val description: String,
    val detail: String = "",   // expanded detail shown when step is tapped
    val codeSnippet: String = "", // URL / command / value to copy
    val badgeColor: Color = Color(0xFF1A1A2E),
    val isNew: Boolean = false // shows NEW tag until user views the step
)

data class GuideSection(
    val id: String,
    val label: String,
    val emoji: String,
    val accentColor: Color,
    val intro: String,
    val steps: List<GuideStep>
)

// ─── App Info ─────────────────────────────────────────────────────────────────

data class AppInfoItem(
    val icon: String,
    val label: String,
    val value: String
)

data class FeatureItem(
    val icon: String,
    val title: String,
    val description: String
)

data class ProviderInfo(
    val emoji: String,
    val name: String,
    val models: String
)

const val APP_VERSION_NAME = "1.0.0"
const val APP_VERSION_CODE = 1

val APP_INFO_ITEMS = listOf(
    AppInfoItem("📦", "Version", "$APP_VERSION_NAME (build $APP_VERSION_CODE)"),
    AppInfoItem("🤖", "Min Android", "8.0 (API 26)"),
    AppInfoItem("🎯", "Target Android", "14 (API 34)"),
    AppInfoItem("🏠", "Package", "ai.zeroclaw.android"),
    AppInfoItem("🛠️", "Built With", "Kotlin + Jetpack Compose"),
    AppInfoItem("📄", "License", "Open Source")
)

val APP_FEATURES = listOf(
    FeatureItem("🦀", "24/7 AI Agent Daemon",
        "Runs as a persistent foreground service on your phone — always on, always listening. No PC or cloud server required."),
    FeatureItem("🔄", "Multi-Provider Failover",
        "Configure multiple API keys across providers. If one fails or hits rate limits, ZeroClaw automatically falls through to the next — zero downtime."),
    FeatureItem("🔌", "11 Messaging Channels",
        "Telegram, WhatsApp, Discord, Signal, Slack, Matrix, IRC, Microsoft Teams, Twitch, LINE, and built-in Web Chat — all native, all with per-user AI history."),
    FeatureItem("🌐", "Cloudflare / ngrok Tunnel",
        "Expose your phone to the internet with one tap. Supports Cloudflare Tunnel (free, no account) and ngrok."),
    FeatureItem("📱", "Offline AI Models (LiteRT LM)",
        "Run AI models directly on your device using LiteRT LM — supports Gemma 4 with 32K context, streaming, thinking mode. Load .litertlm or .bin models from storage. No internet needed."),
    FeatureItem("🔑", "API Key Manager",
        "Add, reorder, test, and manage keys for OpenAI, Anthropic, Google Gemini, OpenRouter, and Ollama. Drag to set priority order."),
    FeatureItem("🔧", "AI Tools (36 built-in)",
        "Web Search, Web Fetch, Memory, PDF Reader, Document Knowledge Graph, Image Analysis, Scheduled Tasks, Status, GitHub, Notion, Email, Weather, Summarize, Translate, Image Gen, Speech-to-Text, Text-to-Speech, Calendar, Contacts, Location, Calculator, RSS Feed, QR Code, File Manager, Clipboard, Spotify, Smart Home, Brave Search, Bookmarks, WebView Browser, Media Pipeline, Composio (1000+ apps), Delegate, Spawn, MessageTool, and Pushover. Toggle each on/off in Settings."),
    FeatureItem("🔍", "Google Search Grounding (Gemini)",
        "Enable per-key Google Search grounding for Gemini API calls. Replies include real-time web info — same as the Gemini app."),
    FeatureItem("🧠", "Advanced AI (8 features)",
        "Custom prompts, streaming responses, multi-agent system, agent profiles (6 personas), workflow engine, tool loop detection, thinking mode, and auto-summarization of long conversations."),
    FeatureItem("🔮", "Vector Memory & RAG (5 features)",
        "Semantic search with embeddings (OpenAI/Gemini), hybrid keyword+vector search with RRF fusion, query expansion with synonyms, temporal decay scoring, and named session management."),
    FeatureItem("🏗️", "Infrastructure & Platform (8 features)",
        "Hooks middleware, user-installable plugins, WebView browser tool, media pipeline, rich push notifications with quick reply, biometric lock, QR device pairing, and WorkManager crash watchdog."),
    FeatureItem("⚙️", "Configuration & UX (10 features)",
        "Export/import settings as JSON, dark/light/system theme, per-channel AI prompts, rate limiting, token usage tracking, tool approval system, conversation labels, home screen widget, voice message transcription, and group chat @mention support."),
    FeatureItem("🚀", "NullClaw Advanced (12 features)",
        "Composio 1000+ app integrations, Delegate/Spawn multi-agent tools, proactive MessageTool, MCP server client, MMR diversity reranking, Adaptive Retrieval, semantic cache, memory hygiene, A2A agent protocol, hint-based routing, AIEOS identity, Pushover notifications, and audit log."),
    FeatureItem("📊", "Document Knowledge Graph",
        "Ingest any PDF, DOCX, or text file into an on-device knowledge graph. Extracts entities, relationships, and text chunks with embeddings. Ask any question about your documents — answers are backed by RAG (semantic search) + graph traversal. Works offline with Gemma 4 or any API provider."),
    FeatureItem("🔋", "Battery Optimized",
        "Smart persistence with boot auto-restart, wake locks, foreground service, and WorkManager watchdog — stays alive even on aggressive OEMs like Samsung and Xiaomi.")
)

val SUPPORTED_PROVIDERS = listOf(
    ProviderInfo("🟢", "OpenAI", "GPT-4o, GPT-4o-mini, o1, o3"),
    ProviderInfo("🟠", "Anthropic", "Claude Haiku, Sonnet, Opus"),
    ProviderInfo("🔵", "Google Gemini", "Gemini Pro, Flash, Ultra"),
    ProviderInfo("🟣", "OpenRouter", "400+ models from all providers"),
    ProviderInfo("⚫", "Ollama", "Local models, no API key needed"),
    ProviderInfo("📱", "Offline (LiteRT LM)", "On-device .litertlm/.bin models, Gemma 4 support")
)

// ─── How It Works ─────────────────────────────────────────────────────────────

val HOW_IT_WORKS = GuideSection(
    id = "how",
    label = "How It Works",
    emoji = "🦀",
    accentColor = Color(0xFFE53935),
    intro = "ZeroClaw turns your Android phone into a 24/7 AI agent server — no PC required. Here's what happens under the hood:",
    steps = listOf(
        GuideStep(1, "📱", "Your Phone Becomes a Server",
            "ZeroClaw runs as a persistent Foreground Service on your device — the same technique used by navigation and music apps to stay alive in the background.",
            "Android's Foreground Service API guarantees the process won't be killed. A permanent notification shows the agent is active. The service auto-restarts after a reboot via the Boot Receiver.",
            badgeColor = Color(0xFFE53935)
        ),
        GuideStep(2, "🧠", "AI Brain (LLM Provider)",
            "Every message your bot receives is sent to an LLM to generate a smart reply. Supports waterfall failover across multiple keys and providers.",
            "You supply your own API keys — ZeroClaw never stores or proxies your keys anywhere outside your device. Supported providers:\n• OpenAI (GPT-4o, GPT-4o-mini)\n• Anthropic (Claude Haiku, Sonnet, Opus)\n• Google Gemini (Pro, Flash)\n• OpenRouter (400+ models)\n• Ollama (local, no API key needed)\n• Offline LiteRT LM (on-device Gemma 4, no internet)",
            badgeColor = Color(0xFFE53935)
        ),
        GuideStep(3, "🌐", "Public Tunnel",
            "Your phone sits behind a home network. ZeroClaw uses a tunnel (Cloudflare or ngrok) to give your phone a public HTTPS URL.",
            "Without a tunnel, platforms like Telegram and Twilio can't send webhooks to your phone. The tunnel creates a bridge:\n  Internet → Tunnel Server → Your Phone\n\nCloudflare Tunnel is built-in and free — no account needed. For a stable permanent URL, use ngrok or Cloudflare with your own domain.",
            badgeColor = Color(0xFFE53935)
        ),
        GuideStep(4, "✈️", "Telegram Channel",
            "The app polls Telegram's API every ~30 seconds for new messages. Each chat gets its own conversation history for context-aware replies.",
            "Polling works without any tunnel or webhook — it's the simplest mode. The bot appears online to users immediately after you tap Start. Per-chat history keeps conversations coherent.",
            badgeColor = Color(0xFFE53935)
        ),
        GuideStep(5, "💬", "WhatsApp Channel",
            "WhatsApp messages arrive via Twilio's platform. Twilio receives the WhatsApp message and forwards it to a webhook on your phone via the tunnel.",
            "Your phone runs a tiny HTTP server on port 8080. Twilio calls that endpoint, your phone processes the message, calls the LLM, and replies back via Twilio's REST API — all in under 3 seconds.",
            badgeColor = Color(0xFFE53935)
        ),
        GuideStep(6, "🔄", "Waterfall Failover",
            "Configure multiple API keys in priority order. If a key fails, hits rate limits, or runs out of credits, ZeroClaw automatically tries the next one.",
            "The failover system tracks each key's health in real-time. You can see success/failure counts on the home screen. Reorder keys via drag-and-drop in the API Keys screen to set your preferred priority.",
            badgeColor = Color(0xFFE53935)
        ),
        GuideStep(7, "🔋", "Battery & Persistence",
            "To keep the agent alive 24/7, disable battery optimization for ZeroClaw and keep the phone plugged in or on a wireless charger.",
            "Go to: Android Settings → Apps → ZeroClaw → Battery → Unrestricted\n\nThe Boot Receiver auto-restarts the service after a reboot if 'Auto-start on Boot' is enabled in Settings.",
            badgeColor = Color(0xFFE53935)
        )
    )
)

// ─── Telegram Guide ───────────────────────────────────────────────────────────

val TELEGRAM_GUIDE = GuideSection(
    id = "telegram",
    label = "Telegram",
    emoji = "✈️",
    accentColor = Color(0xFF229ED9),
    intro = "Set up a Telegram bot in under 5 minutes. You only need the Telegram app — no developer account needed.",
    steps = listOf(
        GuideStep(1, "📲", "Open Telegram & Find BotFather",
            "BotFather is Telegram's official bot that creates and manages all other bots. Open Telegram and search for @BotFather.",
            "Tap the verified @BotFather account (it has a blue checkmark). This is the ONLY official bot — don't use any other account claiming to be BotFather.",
            "@BotFather",
            badgeColor = Color(0xFF229ED9)
        ),
        GuideStep(2, "🤖", "Create Your New Bot",
            "Send /newbot to BotFather. It will ask for a display name and then a username.",
            "Display name: anything you like, e.g. 'My AI Assistant'\nUsername: must end in 'bot', e.g. 'myai_zeroclaw_bot'\n\nBotFather will confirm creation and give you your bot token.",
            "/newbot",
            badgeColor = Color(0xFF229ED9)
        ),
        GuideStep(3, "🔑", "Copy Your Bot Token",
            "BotFather will reply with a token that looks like: 123456789:ABCdefGHIjklMNOpqrSTUvwxYZ",
            "This is your bot's password. Keep it secret — anyone with this token can control your bot.\n\nCopy it exactly as shown (including the colon and everything after it).",
            "Example: 7412356789:AAHbGy3zQl9K2mX8wVpN4cRdTeF6sMjYoui",
            badgeColor = Color(0xFF229ED9)
        ),
        GuideStep(4, "⚙️", "Enter Token in App Settings",
            "Open ZeroClaw Settings (gear icon), scroll to 'Telegram Bot', and paste your token.",
            "Also add your LLM API keys in the API Keys screen (key icon on home). You can add multiple keys for failover.\n\nTap Save when done.",
            badgeColor = Color(0xFF229ED9)
        ),
        GuideStep(5, "▶️", "Start the Service",
            "Go back to the home screen and tap Start. The service will begin polling Telegram for messages.",
            "You'll see 'Telegram connected' appear in the Live Logs section within a few seconds.\n\nThe green status indicator next to Telegram in the Connections card confirms it's live.",
            badgeColor = Color(0xFF229ED9)
        ),
        GuideStep(6, "💬", "Test Your Bot",
            "Open Telegram, search for your bot by its @username, and send it a message. You should get an AI reply in seconds!",
            "If there's no reply after 30 seconds:\n• Check the Live Logs for error messages\n• Verify your bot token is correct in Settings\n• Verify your LLM API key has credits\n• Make sure the ZeroClaw service shows Running",
            badgeColor = Color(0xFF229ED9)
        ),
        GuideStep(7, "✨", "Optional: Customize Your Bot",
            "You can give your bot a profile photo, description, and commands list via BotFather.",
            "Send these commands to @BotFather:\n/setdescription — set a bio\n/setuserpic — upload a profile photo\n/setcommands — define slash commands like /help, /start\n/setprivacy — control if bot sees group messages",
            badgeColor = Color(0xFF229ED9)
        )
    )
)

// ─── WhatsApp Guide ───────────────────────────────────────────────────────────

val WHATSAPP_GUIDE = GuideSection(
    id = "whatsapp",
    label = "WhatsApp",
    emoji = "💬",
    accentColor = Color(0xFF25D366),
    intro = "WhatsApp bots require Twilio as a bridge (official + legal). Twilio has a free sandbox — no payment needed to test.",
    steps = listOf(
        GuideStep(1, "📝", "Create a Free Twilio Account",
            "Go to twilio.com and sign up for a free account. No credit card required for the sandbox.",
            "Visit: https://www.twilio.com/try-twilio\n\nFill in your name, email, and password. Verify your email and phone number. Twilio gives you a free trial balance (~\$15) to start.",
            "https://www.twilio.com/try-twilio",
            badgeColor = Color(0xFF25D366)
        ),
        GuideStep(2, "🔑", "Find Your Account SID & Auth Token",
            "After logging in, go to your Twilio Console dashboard. Your Account SID and Auth Token are shown right on the main page.",
            "Console URL: https://console.twilio.com\n\nAccount SID looks like: ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\nAuth Token looks like: your_auth_token_here\n\nClick the eye icon to reveal the Auth Token.",
            "https://console.twilio.com",
            badgeColor = Color(0xFF25D366)
        ),
        GuideStep(3, "📱", "Activate WhatsApp Sandbox",
            "In Twilio Console, go to Messaging → Try it out → Send a WhatsApp message. This opens the Sandbox setup.",
            "Twilio will show you a sandbox number (like +1 415 523 8886) and a join code.\n\nOpen WhatsApp on your phone and send the join code to that number. Example message: 'join bright-salamander'\n\nThis connects your WhatsApp to the sandbox.",
            "Messaging → Try it out → Send a WhatsApp message",
            badgeColor = Color(0xFF25D366)
        ),
        GuideStep(4, "🌐", "Get Your Public Tunnel URL",
            "Start the ZeroClaw service first. The tunnel URL appears in the Connections card on the home screen.",
            "Your tunnel URL looks like: https://abc123.trycloudflare.com\n\nFor a stable permanent URL, use ngrok with a paid plan or Cloudflare Tunnel with your own domain.\n\nCopy this URL — you'll need it in the next step.",
            badgeColor = Color(0xFF25D366)
        ),
        GuideStep(5, "🔗", "Set Webhook URL in Twilio",
            "In Twilio Console → Messaging → Settings → WhatsApp Sandbox Settings, paste your webhook URL.",
            "Set the 'When a message comes in' field to:\n  YOUR_TUNNEL_URL/whatsapp\n\nExample: https://abc123.trycloudflare.com/whatsapp\n\nMake sure to include /whatsapp at the end. Save the settings.",
            "/whatsapp",
            badgeColor = Color(0xFF25D366)
        ),
        GuideStep(6, "⚙️", "Enter Twilio Credentials in App",
            "Open ZeroClaw Settings and fill in all three Twilio fields.",
            "• Twilio Account SID: ACxxxxxxxx...\n• Twilio Auth Token: your token\n• WhatsApp From Number: whatsapp:+14155238886\n  (This is Twilio's sandbox number — use it exactly as shown with 'whatsapp:' prefix)\n\nTap Save.",
            "whatsapp:+14155238886",
            badgeColor = Color(0xFF25D366)
        ),
        GuideStep(7, "🧪", "Test WhatsApp Bot",
            "Send a message from your WhatsApp to the Twilio sandbox number (+1 415 523 8886). You should get an AI reply!",
            "If it doesn't work:\n• Check the tunnel URL is still active (tunnels expire)\n• Verify the webhook URL in Twilio includes /whatsapp\n• Check Live Logs for 'WhatsApp webhook listening on port 8080'\n• Make sure Twilio sandbox is activated (Step 3)",
            badgeColor = Color(0xFF25D366)
        ),
        GuideStep(8, "🚀", "Go Live (Production)",
            "To move beyond the sandbox, apply for a WhatsApp Business API number in Twilio Console.",
            "Production requires:\n• Meta Business verification\n• Approved WhatsApp Business number\n• Twilio paid plan\n\nFor testing and personal use, the sandbox works indefinitely as long as you re-send the join code every 72 hours.",
            badgeColor = Color(0xFF25D366)
        )
    )
)

// ─── Other Apps Guide ─────────────────────────────────────────────────────────

val OTHER_APPS_GUIDE = GuideSection(
    id = "other",
    label = "Channels",
    emoji = "🔌",
    accentColor = Color(0xFF7C4DFF),
    intro = "ZeroClaw supports 11 messaging channels natively — Telegram, WhatsApp, Discord, Signal, Slack, Matrix, IRC, Teams, Twitch, LINE, and Web Chat. Configure tokens in Settings.",
    steps = listOf(
        GuideStep(1, "🎮", "Discord Bot (Native)",
            "Built-in Discord bot via Gateway WebSocket. Real-time messaging with per-channel AI history and auto-reconnect.",
            "Setup:\n1. Go to: https://discord.com/developers/applications\n2. New Application → Bot → Add Bot\n3. Copy Bot Token\n4. Enable Privileged Gateway Intents: MESSAGE CONTENT INTENT\n5. Generate OAuth2 invite URL (Scopes: bot, Permissions: Send Messages, Read Message History)\n6. Invite bot to your server\n7. Paste Bot Token in ZeroClaw Settings → Discord Bot\n8. Start the service\n\nFeatures:\n• Real-time messaging via Discord Gateway\n• Per-user conversation history\n• Auto-reconnect on disconnect\n• 2000 char message splitting",
            "https://discord.com/developers/applications",
            badgeColor = Color(0xFF5865F2)
        ),
        GuideStep(2, "💼", "Slack Bot (Native)",
            "Built-in Slack bot via Socket Mode WebSocket. Connects to your workspace and responds to messages in real-time.",
            "Setup:\n1. Go to: https://api.slack.com/apps\n2. Create New App → From scratch\n3. Enable Socket Mode (get App-Level Token starting with xapp-)\n4. Subscribe to events: message.channels, message.im\n5. Add Bot Token Scopes: chat:write, channels:history, im:history\n6. Install app to workspace → copy Bot Token (xoxb-...)\n7. In ZeroClaw Settings → Slack Bot, enter: xoxb-BOT_TOKEN|xapp-APP_TOKEN\n8. Start the service\n\nFeatures:\n• Socket Mode — no public URL needed\n• Per-user conversation history\n• Auto-reconnect with WebSocket\n• 3000 char message splitting",
            "https://api.slack.com/apps",
            badgeColor = Color(0xFF4A154B),
            isNew = true
        ),
        GuideStep(3, "📡", "Signal (via Signal-CLI)",
            "Built-in Signal bridge via signal-cli REST API. Polls for messages and replies automatically.",
            "Setup:\n1. Install signal-cli REST API (Docker recommended)\n2. Register a phone number with Signal\n3. Start signal-cli in REST mode\n4. In ZeroClaw Settings → Signal, enter the API URL (e.g. http://192.168.1.100:8080)\n5. Start the service\n\nWarning: Using automation with Signal may violate their ToS. Use a dedicated number.",
            "https://github.com/AsamK/signal-cli",
            badgeColor = Color(0xFF2C6BED)
        ),
        GuideStep(4, "🟣", "Matrix Bot (Native)",
            "Built-in Matrix/Element bot via Client-Server API. Long-polls /sync for real-time messaging on any homeserver.",
            "Setup:\n1. Create a Matrix account for your bot (e.g. on matrix.org)\n2. Get an access token (via Element: Settings → Help & About → Access Token)\n3. In ZeroClaw Settings → Matrix Bot, enter: https://matrix.org|YOUR_ACCESS_TOKEN\n4. Start the service — bot syncs and responds to m.room.message events\n\nFeatures:\n• Works with any Matrix homeserver\n• Per-room conversation history\n• Auto-reconnect with long-polling\n• Federated — reach users on any server",
            "https://matrix.org",
            badgeColor = Color(0xFF0DBD8B),
            isNew = true
        ),
        GuideStep(5, "📡", "IRC Bot (Native)",
            "Built-in IRC bot via raw TCP. Connects to any IRC server, joins channels, responds when mentioned.",
            "Setup:\n1. Choose an IRC server (e.g. irc.libera.chat:6667)\n2. Pick a nickname for your bot\n3. In ZeroClaw Settings → IRC Bot, enter: irc.libera.chat:6667|mybot|#channel1,#channel2\n4. Start the service\n\nFeatures:\n• Raw TCP IRC protocol\n• Responds to mentions and DMs\n• Multi-channel support\n• 400 char message chunking\n• Auto-reconnect on disconnect",
            badgeColor = Color(0xFF7C4DFF),
            isNew = true
        ),
        GuideStep(6, "💼", "Microsoft Teams Bot (Native)",
            "Built-in Teams bot via Bot Framework webhook. Receives activity POSTs and replies via Bot Framework REST API.",
            "Setup:\n1. Register a bot at: https://dev.botframework.com/bots/new\n2. Get your Bot ID and Bot Secret from Azure Bot registration\n3. Set messaging endpoint to YOUR_TUNNEL_URL:3978/api/messages\n4. In ZeroClaw Settings → Teams Bot, enter: botId|botSecret\n5. Start the service (requires tunnel for webhook)\n\nFeatures:\n• Bot Framework v4 compatible\n• OAuth token auto-refresh\n• Per-user conversation history\n• Works with Teams channels and DMs",
            "https://dev.botframework.com",
            badgeColor = Color(0xFF6264A7),
            isNew = true
        ),
        GuideStep(7, "🎮", "Twitch Bot (Native)",
            "Built-in Twitch chat bot via IRC over SSL (TMI). Joins channels and responds when @mentioned.",
            "Setup:\n1. Get OAuth token from: https://twitchapps.com/tmi/\n2. In ZeroClaw Settings → Twitch Bot, enter: oauth:YOUR_TOKEN|botname|#channel1,#channel2\n3. Start the service\n\nFeatures:\n• IRC over SSL (TMI protocol)\n• Responds only when @mentioned\n• Display name extraction from tags\n• 450 char message chunking\n• Rate-limited replies (1.5s between messages)\n• Auto-reconnect on disconnect",
            "https://twitchapps.com/tmi/",
            badgeColor = Color(0xFF9146FF),
            isNew = true
        ),
        GuideStep(8, "💚", "LINE Bot (Native)",
            "Built-in LINE bot via Messaging API webhook. Receives messages and replies using LINE's reply endpoint.",
            "Setup:\n1. Create a LINE Messaging API channel at: https://developers.line.biz\n2. Get your Channel Access Token (long-lived)\n3. Set webhook URL to YOUR_TUNNEL_URL:8443\n4. In ZeroClaw Settings → LINE Bot, paste your Channel Access Token\n5. Start the service (requires tunnel for webhook)\n\nFeatures:\n• LINE Messaging API v2\n• Per-user conversation history\n• 5000 char message chunking (max 5 bubbles)\n• Webhook on port 8443",
            "https://developers.line.biz",
            badgeColor = Color(0xFF00B900),
            isNew = true
        ),
        GuideStep(9, "🌐", "Web Chat (Built-in)",
            "Built-in web chat UI served on port 8088. Access from any browser on the same network or via tunnel URL.",
            "Setup:\n1. In ZeroClaw Settings → Web Chat, enable the toggle\n2. Start the service\n3. Open browser: http://PHONE_IP:8088 (local) or TUNNEL_URL:8088 (remote)\n\nFeatures:\n• Dark-themed responsive chat UI\n• No login required\n• Real-time AI responses\n• Session-based conversation history\n• Works on mobile and desktop browsers\n• Zero setup — just enable and go",
            badgeColor = Color(0xFF7C4DFF),
            isNew = true
        ),
        GuideStep(10, "📧", "Email (via SendGrid/Mailgun)",
            "Connect email to ZeroClaw — inbound emails trigger the AI agent and replies are sent back via the Email tool.",
            "Using SendGrid Inbound Parse:\n1. Create SendGrid account (free tier available)\n2. Set up Inbound Parse webhook pointing to YOUR_TUNNEL_URL/email\n3. SendGrid forwards incoming emails as HTTP POST to your phone\n4. ZeroClaw processes and replies via SendGrid send API\n\nAlternatively, use the built-in Email tool to send emails from chat.",
            "https://sendgrid.com/solutions/email-api/",
            badgeColor = Color(0xFF1A82E2)
        ),
        GuideStep(11, "🤖", "Custom Webhook (Any Platform)",
            "Any platform that can send HTTP POST requests can be connected to ZeroClaw as a custom channel.",
            "ZeroClaw's webhook server listens on port 8080. Send POST requests in this format:\n\nPOST YOUR_TUNNEL_URL/custom\nContent-Type: application/json\n{\n  \"from\": \"user_id\",\n  \"message\": \"Hello!\",\n  \"channel\": \"my_platform\"\n}\n\nThe response will contain the AI reply you can forward to the user.",
            "POST /custom { \"from\": \"id\", \"message\": \"text\" }",
            badgeColor = Color(0xFF7C4DFF)
        )
    )
)

// ─── AI Tools Guide ──────────────────────────────────────────────────────────

val AI_TOOLS_GUIDE = GuideSection(
    id = "tools",
    label = "AI Tools",
    emoji = "🔧",
    accentColor = Color(0xFFFF6F00),
    intro = "ZeroClaw's AI can use tools during conversations — search the web, remember things, read PDFs, analyze images, and run scheduled tasks. Toggle each tool on/off in Settings.",
    steps = listOf(
        GuideStep(1, "🔍", "Web Search",
            "The AI can search the web in real-time using DuckDuckGo — no API key needed. Ask about current events, news, or anything requiring up-to-date info.",
            "How it works:\n• AI detects your question needs web info\n• Sends query to DuckDuckGo HTML search\n• Parses top 5 results (title, snippet, URL)\n• Uses results to give you an informed answer\n\nExample prompts:\n• \"What's the latest news about Android 16?\"\n• \"Who won the game last night?\"\n• \"What's the weather in Tokyo?\"\n\nNo setup needed — works out of the box with any LLM provider.",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(2, "🌐", "Web Fetch",
            "The AI can fetch and read any webpage — articles, docs, blogs. It strips HTML and extracts clean readable text for summarization.",
            "How it works:\n• AI receives a URL from your message\n• Downloads the page content\n• Strips scripts, styles, nav, footer, ads\n• Extracts clean text (max 6000 chars)\n• Summarizes or answers questions about it\n\nExample prompts:\n• \"Summarize this article: https://example.com/post\"\n• \"What does this page say? [URL]\"\n• \"Read this doc and explain it: [URL]\"\n\nSupports any public URL — articles, documentation, blog posts, etc.",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(3, "🧠", "Memory (Persistent)",
            "The AI can remember things about you across conversations. It stores, recalls, and forgets memories per user — powered by a local SQLite database.",
            "How it works:\n• AI stores key-value memories per user in Room/SQLite\n• Memories survive app restarts — fully persistent\n• Each Telegram/WhatsApp user gets their own memory space\n• AI auto-detects when to store or recall\n\nActions:\n• store — save a fact (key + value)\n• recall — retrieve by key, search, or list all\n• forget — delete a specific memory or all\n\nExample prompts:\n• \"Remember that my favorite color is blue\"\n• \"What do you remember about me?\"\n• \"Forget my birthday\"\n\nMemories are stored locally on your device — never sent anywhere.",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(4, "📄", "PDF Reader",
            "The AI can read and extract text from PDF files — local files on your device, content URIs, or remote PDF URLs.",
            "How it works:\n• Uses Android PdfRenderer for page info\n• Extracts text from PDF byte streams (BT/ET operators)\n• Supports local file paths, content:// URIs, and HTTP URLs\n• Remote PDFs are downloaded to cache first\n• Returns extracted text (max 5000 chars)\n\nExample prompts:\n• \"Read this PDF and summarize it: [URL]\"\n• \"What does this document say? [file path]\"\n\nNote: Works best with text-based PDFs. Scanned/image-only PDFs may return limited text (they need OCR).",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(5, "🖼️", "Image Analysis",
            "The AI can analyze images using vision-capable models like GPT-4o, Gemini, or Claude. Send a file path, content URI, or image URL.",
            "How it works:\n• Loads image from file, URI, or URL\n• Resizes to max 1024px (keeps base64 size reasonable)\n• Sends to a vision-capable model from your API keys\n• Returns a detailed description or analysis\n\nSupported models:\n• OpenAI: GPT-4o, GPT-4o-mini (vision)\n• Google: Gemini Pro, Flash (multimodal)\n• Anthropic: Claude Sonnet, Opus (vision)\n\nExample prompts:\n• \"What's in this image? [URL]\"\n• \"Describe this photo: [file path]\"\n• \"Can you read the text in this screenshot?\"\n\nRequires at least one vision-capable API key configured.",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(6, "⏰", "Scheduled Tasks (Cron)",
            "The AI can schedule recurring tasks that run automatically at set intervals. Great for daily summaries, reminders, or periodic checks.",
            "How it works:\n• AI creates a cron task with a name, interval, and prompt\n• Tasks are persisted in SharedPreferences (survive restarts)\n• Service daemon checks every 60 seconds for due tasks\n• Due tasks are executed via LlmRouter automatically\n• Results are logged in the Live Logs\n\nActions:\n• schedule — create a task (name, interval_minutes, prompt)\n• list — show all scheduled tasks\n• cancel — remove a task by name\n\nExample prompts:\n• \"Schedule a daily news summary every 1440 minutes\"\n• \"Remind me to drink water every 60 minutes\"\n• \"What tasks are scheduled?\"\n• \"Cancel the water reminder\"\n\nIntervals: 1 minute to 7 days (10080 minutes).",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(7, "📊", "Status / Diagnostics",
            "The AI can check its own health — service state, API key status, connections, and recent logs. Ask it how things are going.",
            "How it works:\n• Reads ZeroClaw service state (running/stopped)\n• Checks Telegram & WhatsApp connection status\n• Reports API key health (enabled, failed, models)\n• Shows recent log entries\n\nActions:\n• overview — full status report\n• keys — detailed API key health\n• logs — recent log entries\n• connections — Telegram/WhatsApp/Tunnel state\n\nExample prompts:\n• \"What's the service status?\"\n• \"Are my API keys working?\"\n• \"Show me the recent logs\"\n• \"Is Telegram connected?\"",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(8, "🐙", "GitHub Integration",
            "The AI can search GitHub repos, read READMEs, list issues, and even create issues — all from chat. Inspired by upstream ZeroClaw skills.",
            "How it works:\n• Uses GitHub's public REST API\n• No auth needed for public repos\n• Optional GitHub PAT for private repos & issue creation\n\nActions:\n• search — search repositories by keyword\n• readme — read a repo's README\n• issues — list open issues\n• create_issue — file a new issue (needs token)\n\nExample prompts:\n• \"Search GitHub for Android AI agents\"\n• \"Show me the README for zeroclaw-labs/zeroclaw\"\n• \"What are the open issues on my repo?\"\n• \"Create an issue on my-org/my-repo about the login bug\"",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(9, "📝", "Notion Integration",
            "The AI can interact with your Notion workspace — search pages, read content, create pages, and append text blocks.",
            "How it works:\n• Uses the Notion REST API (v2022-06-28)\n• Requires a Notion Integration token\n• Create one at: notion.so/my-integrations\n\nActions:\n• search — find pages/databases in your workspace\n• read_page — get the content of a specific page\n• create_page — add a new page to a database\n• append — add text blocks to an existing page\n\nExample prompts:\n• \"Search my Notion for meeting notes\"\n• \"Read the project roadmap page\"\n• \"Create a new task in my todo database\"\n• \"Add a note to my daily log page\"\n\nNote: You'll need to share pages with your integration in Notion for it to access them.",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(10, "📧", "Email (SendGrid / Mailgun)",
            "The AI can send emails on your behalf using SendGrid or Mailgun. Great for notifications, summaries, and automated communications.",
            "How it works:\n• Uses SendGrid or Mailgun REST APIs\n• Needs an API key from your email provider\n• Supports draft mode (preview before sending)\n\nActions:\n• send — send an email immediately\n• draft — preview the email without sending\n\nParameters:\n• to — recipient email address\n• subject — email subject line\n• body — email body text\n• api_key — your SendGrid/Mailgun API key\n• provider — 'sendgrid' (default) or 'mailgun'\n\nExample prompts:\n• \"Send an email to john@example.com about the meeting\"\n• \"Draft a summary email of today's work\"",
            badgeColor = Color(0xFFFF6F00)
        ),
        GuideStep(11, "🌤️", "Weather",
            "The AI can check current weather and 3-day forecasts for any location worldwide — no API key needed. Uses wttr.in free service.",
            "How it works:\n• Uses wttr.in — free weather API, no key required\n• Supports city names, zip codes, and coordinates\n• Returns detailed weather data (temp, humidity, wind, UV, etc.)\n\nActions:\n• current — detailed current conditions (default)\n• forecast — 3-day forecast with daily highs/lows\n• brief — one-liner weather summary\n\nExample prompts:\n• \"What's the weather in New York?\"\n• \"Give me a 3-day forecast for Tokyo\"\n• \"Weather in 90210\"\n• \"Brief weather for London\"\n\nNo setup needed — works out of the box with any LLM provider.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(12, "📝", "Summarize",
            "The AI can condense long text or web articles into key bullet points. Works with raw text or URLs — no LLM call needed inside the tool.",
            "How it works:\n• Uses extractive summarization — picks the most important sentences\n• Scores sentences by word frequency, position, cue phrases, and length\n• Can fetch a URL and summarize the page content directly\n• Returns top N key points (default 5, max 15)\n• Works entirely offline — no API key needed for the tool itself\n\nExample prompts:\n• \"Summarize this article: https://example.com/long-post\"\n• \"Give me key points from this text: [paste long text]\"\n• \"Summarize this in 10 sentences\"\n\nGreat for offline models — gives them condensed input they can work with.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(13, "🌐", "Translate",
            "The AI can translate text between 50+ languages. Auto-detects the source language. Uses MyMemory API — free, no API key needed.",
            "How it works:\n• Uses MyMemory Translation API (free, no key)\n• Supports 50+ languages including Hindi, Telugu, Tamil, Japanese, Chinese, Arabic, etc.\n• Auto-detects source language if not specified\n• Returns original + translated text with confidence score\n\nSupported languages:\n• European: Spanish, French, German, Italian, Portuguese, Russian, Dutch, Swedish, Polish...\n• Asian: Japanese, Korean, Chinese, Thai, Vietnamese, Indonesian...\n• Indian: Hindi, Telugu, Tamil, Kannada, Malayalam, Marathi, Gujarati, Bengali, Punjabi, Urdu\n• Others: Arabic, Turkish, Hebrew, Swahili, Filipino...\n\nExample prompts:\n• \"Translate hello world to Spanish\"\n• \"Translate 'good morning' to Hindi\"\n• \"Translate this to Japanese: I love sushi\"\n• \"What is 'thank you' in Telugu?\"\n\nNo setup needed — works out of the box.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(14, "🎨", "Image Generation",
            "The AI can generate images from text descriptions. Free via Pollinations.ai (no key needed), or DALL-E 3 if you have an OpenAI key.",
            "How it works:\n• Primary: Pollinations.ai — 100% free, no API key, no signup\n• Optional: DALL-E 3 via OpenAI (needs API key, falls back to Pollinations on failure)\n• Returns a direct URL to the generated image\n\nSizes:\n• square — 1024x1024 (default)\n• wide — 1792x1024 (landscape)\n• tall — 1024x1792 (portrait)\n\nExample prompts:\n• \"Generate an image of a sunset over mountains\"\n• \"Create a picture of a robot playing guitar\"\n• \"Draw a cat wearing a space suit\"\n• \"Generate a wide landscape of a futuristic city\"\n\nNo setup needed — Pollinations.ai works out of the box. Add OpenAI key for DALL-E quality.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(15, "🎤", "Speech-to-Text",
            "The AI can transcribe audio/voice files to text using OpenAI's Whisper model. Supports mp3, wav, m4a, ogg, webm, flac.",
            "How it works:\n• Uses OpenAI Whisper API for accurate transcription\n• Accepts local files, content:// URIs, or audio URLs\n• Auto-downloads remote audio files to cache\n• Supports 50+ languages with auto-detection\n• Returns full transcribed text\n\nSupported formats:\n• mp3, mp4, wav, m4a, ogg, webm, flac\n\nExample prompts:\n• \"Transcribe this audio: [file path or URL]\"\n• \"What does this voice message say? [audio URL]\"\n• \"Convert this recording to text\"\n\nRequires an OpenAI API key (Whisper model).",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(16, "🔊", "Text-to-Speech",
            "The AI can read text aloud or save speech to an audio file. Uses Android's built-in TTS — free, works offline, no API key.",
            "How it works:\n• Uses Android's native TextToSpeech engine\n• 100% free, works completely offline\n• Supports 30+ languages depending on device\n• Adjustable speed: slow, normal, fast\n• Can save to WAV audio file\n\nActions:\n• speak — read text aloud on the device\n• save — save speech as audio file\n• voices — list available TTS voices\n\nExample prompts:\n• \"Read this aloud: Hello world\"\n• \"Speak this in Spanish: Buenos dias\"\n• \"Save this as audio: Today's summary...\"\n• \"What voices are available?\"\n\nNo setup needed — uses whatever TTS engine is installed on your device (Google, Samsung, etc.).",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(17, "📅", "Calendar",
            "The AI can read and create events on your Android calendar. View today's schedule, search events, or create new ones — all through natural language.",
            "How it works:\n• Reads events from Android's built-in CalendarProvider\n• Can create new events with title, date, time, location\n• Search events by keyword (past 30 days + next 90 days)\n• Supports all-day and timed events\n• Works with Google Calendar, Samsung Calendar, etc.\n\nActions:\n• today — show today's events\n• week — show this week's events\n• list — events for a specific date\n• create — add a new event\n• search — find events by keyword\n\nExample prompts:\n• \"What's on my calendar today?\"\n• \"Show my schedule this week\"\n• \"Create a meeting tomorrow at 3pm called Team Standup\"\n• \"Search calendar for dentist\"\n• \"Add event on 2026-04-01 at 10:00 — Project Review\"\n\nRequires calendar permission — grant when prompted.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(18, "👥", "Contacts",
            "The AI can search and read contacts from your device. Find people by name, list contacts, or get full details — phone, email, address, and more.",
            "How it works:\n• Reads from Android's built-in ContactsProvider\n• Search by name (partial match)\n• Shows phone numbers, emails, organization\n• Detailed view includes address and notes\n\nActions:\n• search — find contacts by name\n• list — show contacts (starred first)\n• details — full info for a contact\n\nExample prompts:\n• \"Find contact John\"\n• \"Look up Sarah's phone number\"\n• \"Show my contacts\"\n• \"What's the email for Dr. Smith?\"\n\nRequires contacts permission — grant when prompted.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(19, "📍", "Location",
            "The AI can get your GPS location, geocode addresses to coordinates, reverse geocode, and find nearby places — all on device or via free APIs.",
            "How it works:\n• GPS — reads device location (last known fix)\n• Geocode — converts address to coordinates\n• Reverse — converts coordinates to address\n• Nearby — search places near you via OpenStreetMap\n• Distance calculated with haversine formula\n\nActions:\n• current — get device GPS location\n• geocode — address to coordinates\n• reverse — coordinates to address\n• nearby — find nearby places by type\n\nExample prompts:\n• \"Where am I?\"\n• \"Get my current location\"\n• \"Geocode 1600 Pennsylvania Ave, Washington DC\"\n• \"Find restaurants near me\"\n• \"What's at coordinates 40.7128, -74.0060?\"\n\nRequires location permission for GPS; geocoding works without permission.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(20, "🔢", "Calculator",
            "The AI can evaluate math expressions and convert between units. Supports arithmetic, trig, logarithms, factorials, and 60+ unit conversions — all offline.",
            "How it works:\n• Recursive descent parser — no internet needed\n• Supports: +, -, *, /, **, %, parentheses\n• Functions: sqrt, sin, cos, tan, log, ln, abs, ceil, floor, round, exp\n• Constants: pi, e\n• Factorials: 5!\n• Unit conversions: length, weight, volume, temperature, speed, time, data\n\nActions:\n• eval — evaluate math expression\n• convert — convert between units\n\nExample prompts:\n• \"Calculate sqrt(144) + 5**2\"\n• \"What is sin(45)?\"\n• \"Convert 100 kg to pounds\"\n• \"How many miles is 10 km?\"\n• \"Convert 72°F to celsius\"\n• \"What is 15! (factorial)?\"",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(21, "📰", "RSS Feed",
            "The AI can read RSS and Atom feeds from any blog, news site, or podcast. Get full articles or just headlines.",
            "How it works:\n• Fetches and parses RSS 2.0 and Atom feeds\n• Supports any public feed URL\n• No API key needed\n\nActions:\n• read — full feed with descriptions\n• headlines — titles only (more items)\n\nExample prompts:\n• \"Read the RSS feed at https://blog.example.com/feed\"\n• \"Get headlines from https://news.ycombinator.com/rss\"\n• \"What's new on this feed: https://example.com/rss.xml\"\n\nWorks with blogs, news, podcasts, YouTube channels, Reddit, and more.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(22, "📱", "QR Code",
            "The AI can generate QR codes from text, URLs, or any data. Creates a PNG image file on the device.",
            "How it works:\n• Built-in QR code encoder — no internet or library needed\n• Generates PNG images up to any size\n• Supports text, URLs, WiFi configs, contact cards, etc.\n\nActions:\n• generate — create QR code image from text\n\nExample prompts:\n• \"Generate a QR code for https://example.com\"\n• \"Create a QR code with my WiFi password\"\n• \"Make a QR code that says Hello World\"\n\nMax text length: 2953 characters. QR images saved to app cache.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(23, "📂", "File Manager",
            "The AI can list, read, write, and search files on your device. Works with app storage, downloads, documents, and more.",
            "How it works:\n• List directory contents with sizes and dates\n• Read text files (up to 1MB)\n• Write/append to files\n• Search files by name pattern\n• File info: size, modified date, permissions\n\nActions:\n• list — show directory contents\n• read — read a text file\n• write — create or overwrite a file\n• info — detailed file information\n• search — find files by name\n\nPath shortcuts:\n• 'downloads' — Downloads folder\n• 'documents' — Documents folder\n• 'internal' — App internal storage\n• 'cache' — App cache directory\n\nExample prompts:\n• \"List files in downloads\"\n• \"Read the file notes.txt\"\n• \"Write 'Hello' to internal/test.txt\"\n• \"Search downloads for .pdf files\"",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(24, "📋", "Clipboard",
            "The AI can read from and write to your device clipboard. Copy text for quick sharing or check what's currently copied.",
            "How it works:\n• Read current clipboard content\n• Copy text to clipboard for pasting elsewhere\n• Clear clipboard when needed\n\nActions:\n• read — get current clipboard content\n• write — copy text to clipboard\n• clear — clear the clipboard\n\nExample prompts:\n• \"What's on my clipboard?\"\n• \"Copy this to clipboard: Hello World\"\n• \"Clear my clipboard\"\n• \"Read clipboard\"",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(25, "🎵", "Spotify",
            "The AI can control Spotify playback — search music, play/pause, skip tracks, check what's playing, and add to queue.",
            "How it works:\n• Uses Spotify Web API\n• Requires a Spotify access token\n• Controls your active Spotify session\n\nActions:\n• search — find tracks, artists, albums\n• play — play a track or resume\n• pause — pause playback\n• next/previous — skip tracks\n• now_playing — what's currently playing\n• queue — add a track to queue\n\nExample prompts:\n• \"What's playing on Spotify?\"\n• \"Play Bohemian Rhapsody on Spotify\"\n• \"Search Spotify for Taylor Swift\"\n• \"Skip to next track\"\n• \"Add Imagine by John Lennon to queue\"\n\nRequires Spotify Premium for playback control.",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(26, "🏠", "Smart Home",
            "The AI can control Philips Hue smart lights — turn on/off, set brightness, change colors, and activate scenes.",
            "How it works:\n• Connects to Philips Hue Bridge on local network\n• Requires Bridge IP address and API username\n• Controls individual lights or groups\n\nActions:\n• lights — list all connected lights\n• on/off — turn light on or off\n• brightness — set brightness (0-100%)\n• color — change light color\n• status — check light details\n• scenes — list or activate scenes\n\nSupported colors:\nred, orange, yellow, green, cyan, blue, purple, pink, white, warm, cool\n\nExample prompts:\n• \"List my Hue lights\"\n• \"Turn on the living room light\"\n• \"Set bedroom to 50% brightness\"\n• \"Change kitchen light to blue\"\n• \"Activate the Relax scene\"",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(27, "🦁", "Brave Search",
            "The AI can search the web using the Brave Search API — an alternative to DuckDuckGo with richer results and info boxes.",
            "How it works:\n• Uses Brave Search REST API\n• Returns titles, URLs, descriptions, info boxes\n• Supports web search and news search\n• Free tier: 2000 queries/month\n\nActions:\n• web — general web search\n• news — news-specific search\n\nExample prompts:\n• \"Brave search for Kotlin coroutines\"\n• \"Search Brave for latest tech news\"\n• \"Brave news about AI\"\n\nRequires a Brave Search API key — get one free at api.search.brave.com",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(28, "🔖", "Bookmarks",
            "The AI can save, list, search, and delete bookmarks for each user. Stored locally on the device with Room database.",
            "How it works:\n• Per-user bookmark storage using Room/SQLite\n• Save URLs with titles and tags\n• Search bookmarks by keyword\n• Duplicate URL detection (updates existing)\n\nActions:\n• save — add a bookmark (url + title + tags)\n• list — show all bookmarks\n• search — find by keyword\n• delete — remove by ID\n\nExample prompts:\n• \"Bookmark https://example.com as My Site\"\n• \"Save this URL: https://docs.kotlin.org tagged kotlin,docs\"\n• \"Show my bookmarks\"\n• \"Search bookmarks for kotlin\"\n• \"Delete bookmark 3\"",
            badgeColor = Color(0xFFFF6F00),
            isNew = true
        ),
        GuideStep(29, "⚙️", "Managing Tools",
            "Toggle each tool on/off in Settings. Disabled tools won't be offered to the AI. Use /tools in chat to see which tools are currently enabled.",
            "How to manage:\n1. Go to Settings (gear icon)\n2. Scroll to 'AI Tools' section\n3. Toggle each tool on or off\n4. Changes take effect immediately\n\nIn chat:\n• Send /tools to list all enabled tools\n• The AI only uses tools when your question genuinely needs them\n• Tools work with ALL providers — OpenAI, Anthropic, Gemini, OpenRouter, Ollama\n\nTool calls are shown in Live Logs:\n  TOOL: executing web_search({query=...})\n  TOOL: ✓ web_search returned 1200 chars",
            badgeColor = Color(0xFFFF6F00)
        )
    )
)

// ─── Advanced AI Features Guide ──────────────────────────────────────────────

val ADVANCED_AI_GUIDE = GuideSection(
    id = "advanced_ai",
    label = "Advanced AI",
    emoji = "🧠",
    accentColor = Color(0xFF9C27B0),
    intro = "ZeroClaw's advanced AI features: custom personas, streaming, multi-agent orchestration, workflows, thinking mode, and automatic conversation management.",
    steps = listOf(
        GuideStep(1, "🎭", "Custom System Prompts",
            "Configure different AI personas per-channel and per-user. Telegram gets casual, Teams gets professional — automatically.",
            "How it works:\n• Per-channel prompts: set a different system prompt for Telegram vs Discord vs Slack etc.\n• Per-user prompts: override for specific users/chats\n• Global prompt: override the default for all channels\n• Priority: user-specific > channel-specific > global > default\n\nSet via agent profiles or programmatically. The AI automatically uses the right persona based on where the message comes from.\n\nIn chat:\n• /profile — list available profiles\n• /profile <id> — switch to a profile",
            badgeColor = Color(0xFF9C27B0),
            isNew = true
        ),
        GuideStep(2, "⚡", "Streaming Responses",
            "Stream LLM output to Telegram/Discord in real-time. See the AI typing its response word by word — like ChatGPT.",
            "How it works:\n• Uses Server-Sent Events (SSE) for OpenAI and Anthropic APIs\n• Text chunks emitted as they arrive from the model\n• Channels can edit messages in-place as chunks arrive\n• Rate-limited updates (every 1.5s) to avoid API spam\n\nSupported providers:\n• OpenAI-compatible (GPT-4o, etc.)\n• Anthropic Claude (Haiku, Sonnet, Opus)\n\nThe streaming experience depends on the channel's edit capabilities. Telegram and Discord support message editing for progressive display.",
            badgeColor = Color(0xFF9C27B0),
            isNew = true
        ),
        GuideStep(3, "🤖", "Multi-Agent System",
            "Spawn sub-agents for parallel tasks. The main AI can delegate work to specialized sub-agents that run concurrently.",
            "How it works:\n• Main agent spawns sub-agents for parallel tasks\n• Each sub-agent gets its own chat context\n• Max 5 concurrent agents, 3 depth levels\n• 2-minute timeout per sub-agent\n• Results collected and merged\n\nCapabilities:\n• spawn — create a new sub-agent with a task\n• spawnAndWait — spawn multiple agents, wait for all\n• cancel — stop a running agent\n• list — see all active agents\n• sweep — clean up completed agents\n\nExample: \"Research 3 topics simultaneously\" → spawns 3 sub-agents, merges findings.",
            badgeColor = Color(0xFF9C27B0),
            isNew = true
        ),
        GuideStep(4, "🎭", "Agent Profiles (Personas)",
            "Named AI personas with different prompts, models, and personalities. Switch instantly between Coder, Creative Writer, Analyst, and more.",
            "Built-in profiles:\n• 🦀 ZeroClaw — default helpful assistant\n• 💻 Code Assistant — senior engineer, clean code\n• ✨ Creative Writer — vivid imagination, literary style\n• 📊 Data Analyst — precise, data-driven, structured\n• 📚 Patient Tutor — step-by-step, encouraging\n• ⚡ Brief Mode — ultra-concise, bullet points\n\nCreate custom profiles with:\n• Custom system prompt\n• Preferred model/provider\n• Enabled tools subset\n• Temperature and max tokens\n\nIn chat:\n• /profile — list all profiles\n• /profile coder — switch to Code Assistant\n• /profile creative — switch to Creative Writer",
            badgeColor = Color(0xFF9C27B0),
            isNew = true
        ),
        GuideStep(5, "🔄", "Workflow Engine",
            "Multi-step automated pipelines where each step's output feeds into the next. Research → Summarize → Translate → Email.",
            "How it works:\n• Define workflows as chains of LLM calls and tool calls\n• {input} placeholder for user input\n• {prev_result} placeholder for previous step output\n• Conditional steps: skip based on content/length\n• Max 10 steps per workflow\n\nBuilt-in templates:\n• Research & Summarize — search web, then summarize\n• Translate & Email — translate text, draft email\n• Analyze & Report — analyze data, create structured report\n\nCreate custom workflows with any combination of LLM prompts and tool invocations.",
            badgeColor = Color(0xFF9C27B0),
            isNew = true
        ),
        GuideStep(6, "🛡️", "Tool Loop Detection",
            "Automatically detects and breaks infinite tool call chains. Prevents the AI from getting stuck calling the same tools repeatedly.",
            "Detection methods:\n• Repeat detection — same tool+args called 3 times → break\n• Circuit breaker — 15 total tool calls per turn → stop\n• Ping-pong — A→B→A→B oscillation pattern → break\n• Stall detection — 3 rounds with identical results → stop\n\nWhen a loop is detected:\n• The tool chain is interrupted\n• The AI is given accumulated results so far\n• A warning is logged for debugging\n\nThis prevents runaway API costs and stuck conversations.",
            badgeColor = Color(0xFF9C27B0),
            isNew = true
        ),
        GuideStep(7, "💭", "Thinking Mode",
            "Extended reasoning for complex problems. The AI breaks down hard questions step by step before answering.",
            "How it works:\n• Auto-detects complex questions (comparison, analysis, debugging, design)\n• Wraps the query in a chain-of-thought prompt\n• AI produces <thinking> reasoning + <answer> final response\n• Only the polished answer is shown to the user\n• Reasoning logged for debugging\n\nTriggers:\n• Automatic: 2+ complexity indicators detected\n• Explicit: \"think step by step\", \"reason through\", \"show your work\"\n\nComplexity detection:\n• Comparison/contrast questions\n• Why/how analysis\n• Design/architecture problems\n• Multi-step math/logic\n• Long, multi-part questions",
            badgeColor = Color(0xFF9C27B0),
            isNew = true
        ),
        GuideStep(8, "📝", "Conversation Summarization",
            "Automatically compresses long chat histories to stay within context limits. Old messages are summarized, recent ones kept intact.",
            "How it works:\n• Monitors conversation length (~3000 token threshold)\n• When exceeded, older messages are summarized\n• Last 6 messages always kept intact\n• Extractive summarization — no extra LLM call needed\n• Preserves: topics, key facts, URLs, decisions\n• Summary replaces old messages in history\n\nWhat's preserved:\n• User questions and requests\n• Key AI response points\n• URLs, emails, identifiers\n• Active tasks and decisions\n\nThis allows unlimited-length conversations without hitting context window limits or losing important context.",
            badgeColor = Color(0xFF9C27B0),
            isNew = true
        )
    )
)

// ─── All sections list ─────────────────────────────────────────────────────────

// ─── Memory & Vector Guide ────────────────────────────────────────────────────

val MEMORY_GUIDE = GuideSection(
    id = "memory_rag",
    label = "Memory & RAG",
    emoji = "🔮",
    accentColor = Color(0xFF00BCD4),
    intro = "ZeroClaw's memory system goes beyond simple key-value storage. It uses semantic embeddings, hybrid search, and temporal decay to find the most relevant memories.",
    steps = listOf(
        GuideStep(1, "🧠", "Vector Memory (Semantic Search)",
            "Memories are encoded as embedding vectors. Semantic search finds relevant memories even when keywords don't match — by meaning, not just words.",
            "How it works:\n• When storing a memory, an embedding vector is generated via OpenAI text-embedding-3-small or Gemini text-embedding-004\n• Vectors stored as JSON in SQLite alongside the memory text\n• On recall, the query is also embedded and cosine similarity is computed\n• Results above 35% similarity threshold are returned, ranked by score\n\nProviders tried in order:\n1. OpenAI text-embedding-3-small (1536 dims, best quality)\n2. Gemini text-embedding-004 (768 dims)\n3. TF-IDF fallback (256 dims, offline, no API key needed)\n\nExample: Storing 'I enjoy Italian food' allows finding it when asking 'what cuisine do I like?' — without exact keyword match.",
            badgeColor = Color(0xFF00BCD4),
            isNew = true
        ),
        GuideStep(2, "🔀", "Hybrid Search (Vector + Keyword)",
            "Combines semantic vector search with keyword LIKE search using Reciprocal Rank Fusion (RRF). Gets the best of both worlds.",
            "How it works:\n• Both search methods run in parallel\n• Results merged using RRF (Reciprocal Rank Fusion) algorithm\n• Weights: 60% vector similarity + 40% keyword overlap\n• Deduplication and score normalization applied\n• Temporal decay applied to final ranked list\n\nRRF formula: score = 1/(k+rank_vector) × 0.6 + 1/(k+rank_keyword) × 0.4\nwhere k=60 (Cormack et al. constant)\n\nThis means a memory that ranks #2 in both keyword AND vector search will score higher than one that ranks #1 in only one method.",
            badgeColor = Color(0xFF00BCD4),
            isNew = true
        ),
        GuideStep(3, "🔍", "Query Expansion",
            "The search query is automatically expanded with synonyms and related terms before searching, catching memories stored with different vocabulary.",
            "How it works:\n• Stop words removed (the, is, a, for...)\n• Keywords extracted from the query\n• Synonyms looked up in built-in synonym groups\n• Expanded queries searched separately\n• Best scores merged\n\nExample expansions:\n• 'remember my job' → also searches: work, career, profession, occupation\n• 'my birthday' → also searches: birth date, born, date of birth, dob\n• 'phone number' → also searches: mobile, cell, telephone, contact\n\nResult: recalling 'what's my profession?' finds 'job: software engineer' even though 'profession' ≠ 'job'.",
            badgeColor = Color(0xFF00BCD4),
            isNew = true
        ),
        GuideStep(4, "⏳", "Temporal Decay",
            "Recent memories score higher than old ones. A 30-day-old memory gets 50% of its original score. Pinned memories never decay.",
            "How it works:\n• Decay formula: score × e^(-λ × age_days)\n• Default half-life: 30 days (score halves every 30 days)\n• < 1 day old: +15% recency bonus\n• Pinned memories (tag: 'pinned'): +50% score, no decay\n\nDecay schedule:\n• 1 day: 97% of original score\n• 7 days: 86%\n• 30 days: 50% (half-life)\n• 90 days: 12.5%\n• 180 days: 1.6%\n\nPin important memories: 'remember my name is John' + tag: pinned\nPinned memories always appear at top of recall results.",
            badgeColor = Color(0xFF00BCD4),
            isNew = true
        ),
        GuideStep(5, "📁", "Named Sessions",
            "Create separate memory + conversation sessions for different contexts. Switch between Work, Personal, Projects without mixing histories.",
            "How it works:\n• Each session has isolated conversation history in LlmRouter\n• Memories can be tagged with session IDs\n• Switching sessions changes the chat context used\n• Sessions can be exported as JSON (conversation + memories)\n\nIn chat:\n• 'create session Work' — new work session\n• 'switch to session Work' — activate it\n• 'list sessions' — show all sessions\n• 'export session Work' — download as JSON\n\nSession chat IDs: userId_session_sessionId\nThis ensures conversation history is fully isolated per session.",
            badgeColor = Color(0xFF00BCD4),
            isNew = true
        )
    )
)

// ─── Infrastructure & Platform Guide ──────────────────────────────────────────

val INFRA_GUIDE = GuideSection(
    id = "infra",
    label = "Infrastructure",
    emoji = "🏗️",
    accentColor = Color(0xFF607D8B),
    intro = "ZeroClaw's infrastructure layer: plugin system, browser automation, media processing, secure notifications, biometric lock, device pairing, and crash recovery.",
    steps = listOf(
        GuideStep(1, "🪝", "Hooks System",
            "Lifecycle middleware that fires before/after every tool call, LLM call, message, and session event. Intercept and transform any data in the pipeline.",
            "Hook points:\n• BEFORE_TOOL_CALL — modify args or cancel before a tool runs\n• AFTER_TOOL_CALL — transform the tool result\n• BEFORE_LLM_CALL — modify the prompt before sending to the LLM\n• AFTER_LLM_CALL — transform the LLM reply\n• MESSAGE_RECEIVED — intercept incoming messages from any channel\n• MESSAGE_SENDING — sanitize or transform outgoing messages\n• SESSION_START / SESSION_END — lifecycle events\n\nBuilt-in hooks:\n• LOG_TOOL_CALLS — logs every tool invocation\n• SANITIZE_OUTPUT — strips <thinking> tags from sent messages\n\nPlugins can register additional hooks from their manifest.",
            badgeColor = Color(0xFF607D8B),
            isNew = true
        ),
        GuideStep(2, "🔌", "Plugin System",
            "Install custom skill packages as .zip files. Plugins add new tools and hook handlers without recompiling the app.",
            "Plugin .zip structure:\n• manifest.json — metadata, tool definitions, hook registrations\n• tools/*.json — per-tool configs (name, description, params, endpoint)\n• README.md — optional documentation\n\nPlugin tools call a user-defined HTTP endpoint with tool args and return the response.\n\nTo install a plugin:\n1. Download a .zip plugin file to your device\n2. Open Settings → Plugins → Install from file\n3. Select the .zip — it's extracted and activated immediately\n\nPlugins can be enabled/disabled or uninstalled at any time.",
            badgeColor = Color(0xFF607D8B),
            isNew = true
        ),
        GuideStep(3, "🌐", "WebView Browser Tool",
            "Load and interact with web pages using a real browser engine. Renders JavaScript-heavy sites, fills forms, and extracts structured content.",
            "Actions:\n• fetch — load URL, wait for JS to execute, return visible text\n• scrape — extract structured data (title, headings, content preview)\n• js — execute custom JavaScript on a loaded page and return result\n\nExample uses:\n• Render React/Vue SPAs that regular fetch can't handle\n• Fill login forms and click buttons via JS injection\n• Extract dynamic content that requires JavaScript to render\n• Scrape paginated data after JS navigation\n\nTool name: webview\nParameters: url, action, script (optional), wait_ms (optional)",
            badgeColor = Color(0xFF607D8B),
            isNew = true
        ),
        GuideStep(4, "🎬", "Media Pipeline Tool",
            "Process images, audio, and video files on-device. No API key or internet needed — uses Android's built-in media APIs.",
            "Actions:\n• image_resize — resize to target dimensions, save as JPEG\n• image_info — get dimensions, format, file size\n• audio_info — duration, bitrate, title, artist metadata\n• video_info — duration, resolution, frame rate, bitrate\n• video_frame — extract a frame from a video at a given timestamp\n\nAll processing runs locally using:\n• Android BitmapFactory for image decoding\n• MediaMetadataRetriever for audio/video metadata\n• Bitmap.createScaledBitmap for resize operations\n\nTool name: media_pipeline\nOutput files saved to app cache or a specified output_path.",
            badgeColor = Color(0xFF607D8B),
            isNew = true
        ),
        GuideStep(5, "🔔", "Rich Notifications",
            "Per-channel push notifications with inline Quick Reply. Reply to Telegram or Discord messages directly from the notification shade.",
            "Features:\n• 5 separate notification channels (Telegram, Discord, Slack, WhatsApp, General)\n• Each channel can have distinct sounds and vibration patterns\n• Quick Reply action — type and send a reply without opening the app\n• BigText style for long messages\n• Message grouping by channel (bundled notifications)\n• Reply is routed back through the correct messaging channel\n\nPermissions required:\n• POST_NOTIFICATIONS (Android 13+)\n• VIBRATE\n\nNotification channels are created automatically on first launch.",
            badgeColor = Color(0xFF607D8B),
            isNew = true
        ),
        GuideStep(6, "🔐", "Biometric Lock",
            "Protect the ZeroClaw app with fingerprint or face recognition. Uses Android BiometricPrompt API with device credential fallback.",
            "Supported authentication:\n• Fingerprint sensor\n• Face recognition (device-dependent)\n• Iris scanner (device-dependent)\n• Device PIN/pattern/password as fallback\n\nHow it works:\n• BiometricPrompt shows the system authentication UI\n• Result is returned as a Kotlin suspend function\n• On success: access granted to protected screens\n• On failure/cancel: access denied\n\nEnable in Settings → Security → Biometric Lock.\n\nNote: Biometric lock only protects the app UI — the AI service continues running in the background regardless.",
            badgeColor = Color(0xFF607D8B),
            isNew = true
        ),
        GuideStep(7, "📱", "Device Pairing (QR Code)",
            "Pair a desktop browser or another phone to your ZeroClaw agent via QR code. Generates a one-time token embedded in a QR code for secure local access.",
            "How it works:\n1. Open the Pairing screen — a QR code is generated\n2. The QR encodes your device's local IP + port + a 32-byte random token\n3. Scan with any device on the same WiFi network\n4. The connecting device sends the token to authenticate\n5. Paired session lasts 24 hours\n\nQR payload example:\n{\"host\": \"192.168.1.42:8080\", \"token\": \"a3f8...\", \"name\": \"ZeroClaw\", \"version\": 1}\n\nSessions are revoked when the service stops or via Settings → Security → Revoke All Pairings.",
            badgeColor = Color(0xFF607D8B),
            isNew = true
        ),
        GuideStep(8, "🔄", "Auto Recovery (Watchdog)",
            "WorkManager watchdog that checks every 15 minutes if ZeroClawService is running. Automatically restarts it if the OS killed the process.",
            "Why this is needed:\n• Aggressive OEMs (Samsung, Xiaomi) may kill background services\n• Memory pressure can terminate the service\n• Uncaught exceptions can crash the process\n\nHow it works:\n• WorkManager schedules a periodic job every 15 minutes\n• The job checks if ZeroClawService is in the running processes list\n• If not found: calls startForegroundService() to restart it\n• WorkManager jobs survive reboots and Doze mode\n\nThis is a safety net on top of:\n• Boot Receiver (restarts after reboot)\n• Foreground Service (harder to kill)\n• Wake Lock (keeps CPU awake during processing)",
            badgeColor = Color(0xFF607D8B),
            isNew = true
        )
    )
)

// ─── Configuration & UX Guide ─────────────────────────────────────────────────

val CONFIG_UX_GUIDE = GuideSection(
    id = "config_ux",
    label = "Config & UX",
    emoji = "⚙️",
    accentColor = Color(0xFF4CAF50),
    intro = "Settings backup, theme switching, rate limiting, usage tracking, approval gates, conversation labels, home widget, voice input, and group chat support.",
    steps = listOf(
        GuideStep(1, "💾", "Export & Import Config",
            "Backup all settings and API keys to a JSON file. Restore on a new device or after a factory reset in one tap.",
            "Export creates:\nDownloads/ZeroClaw/zeroclaw_backup_YYYYMMDD_HHmmss.json\n\nContains:\n• All channel tokens (Telegram, Discord, Twilio, etc.)\n• All API keys from the key manager\n• Service settings (auto-start, web chat, etc.)\n\nImport:\n• Select a backup file\n• Settings and keys are restored immediately\n• Duplicate API keys are skipped\n\nSettings → Backup → Export Config / Import Config",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(2, "🎨", "Theme Toggle",
            "Switch between Dark, Light, and System (follow Android) themes. The change takes effect immediately — no restart needed.",
            "Three modes:\n• 🌙 Dark — ZeroClaw's signature dark navy theme\n• ☀️ Light — Clean white/light grey theme\n• 📱 System — Follows your Android dark mode toggle\n\nThe theme preference is persisted in DataStore and observed as a Flow in MainActivity. Changing the theme in Settings triggers instant recomposition across the entire app.\n\nSettings → Appearance → Theme",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(3, "🎭", "Per-Channel Prompts",
            "Set a different AI system prompt for each messaging channel. Telegram gets casual, Teams gets professional — automatically, with no manual switching.",
            "Priority chain:\n1. User-specific prompt (overrides everything for that chat ID)\n2. Channel-specific prompt (e.g. all Telegram chats)\n3. Global custom prompt (overrides default for all channels)\n4. Default ZeroClaw prompt\n\nExample per-channel prompts:\n• Telegram: 'Be casual and friendly, use emojis'\n• Teams: 'Be professional and concise'\n• Discord: 'Be fun and informal'\n\nSet via Settings → AI Behavior → Channel Prompts\nOr in chat: /profile <id> to switch profile",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(4, "🚦", "Rate Limiting",
            "Per-user message rate limits to prevent abuse. Default: 20 messages per minute. Trusted users can be whitelisted.",
            "How it works:\n• Sliding window counter per user ID\n• Each channel tracks separately\n• When exceeded: user gets a friendly message with reset timer\n• No messages are dropped silently\n\nDefault config:\n• 20 messages per 60-second window\n• Enabled for all channels\n\nWhen a user is rate-limited:\n'Rate limit reached. You can send another message in 42s. (Limit: 20/60s)'\n\nConfigure in Settings → Behavior → Rate Limiting",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(5, "📊", "Usage Tracking",
            "Track input/output token counts and estimated API costs per key and model. See exactly what each key is costing you.",
            "What's tracked:\n• Input tokens per LLM call\n• Output tokens per LLM call\n• Call count per key/model combo\n• Estimated cost in USD\n\nPricing built-in for:\n• GPT-4o: \$5/\$15 per 1M tokens\n• GPT-4o-mini: \$0.15/\$0.60\n• Claude Sonnet: \$3/\$15\n• Claude Haiku: \$0.25/\$1.25\n• Gemini Flash: \$0.35/\$1.05\n• And more...\n\nData persisted in DataStore, updated every 10 calls. View the report in Settings → Usage.",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(6, "🔐", "Tool Approval System",
            "Dangerous tool actions (delete files, send emails, control smart home) require your explicit yes/no approval before executing.",
            "Dangerous actions that need approval:\n• file_manager: delete, write, move\n• email: send\n• smart_home: any action\n• web_fetch: POST/PUT/DELETE requests\n• calendar: create, delete, update\n\nWhen triggered:\n1. AI sends you an approval request message\n2. You reply 'yes' to allow or 'no' to deny\n3. 5-minute timeout — auto-denied if no response\n\nApproval message format:\n🔐 Approval Required\nTool: file_manager (delete)\nReason: This will modify or delete files\nReply yes or no",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(7, "🏷️", "Conversation Labels",
            "Tag and pin conversations for easy organization. Label work chats, pin VIP users, add notes — all via simple /commands.",
            "In-chat commands:\n• /label work — add label #work\n• /label vip — add label #vip\n• /unlabel work — remove label\n• /pin — pin this conversation\n• /unpin — unpin\n• /labels — show labels for this chat\n• /labeled work — list all chats labeled #work\n\nLabels persist across sessions and are stored in DataStore. Labels are shown in the conversation list and can be used to filter chats.\n\nExample uses:\n• /label support — tag support conversations\n• /label family — tag family group chats\n• /pin — keep VIP chats at the top",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(8, "🖼️", "Home Screen Widget",
            "Add a ZeroClaw widget to your Android home screen. Shows service status and active connections, with a quick Start/Stop button.",
            "Widget features:\n• 🟢 Running / 🔴 Stopped status indicator\n• Active connection count\n• Start/Stop button without opening the app\n• Tap widget to open ZeroClaw\n• Auto-updates every 30 minutes\n• Updates instantly when service status changes\n\nTo add the widget:\n1. Long-press your home screen\n2. Tap Widgets\n3. Find ZeroClaw in the list\n4. Drag to your home screen\n\nWidget size: 2×1 cells minimum, resizable",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(9, "🎤", "Voice Message Transcription",
            "Automatically transcribe Telegram/WhatsApp voice messages to text before sending to the AI. Powered by OpenAI Whisper (or on-device STT).",
            "How it works:\n1. User sends a voice message (OGG/OPUS format)\n2. ZeroClaw detects it's a voice message\n3. Downloads the audio file to device cache\n4. Sends to SpeechToTextTool (Whisper API)\n5. Transcription appears to AI as: 🎤 [Voice message]: <text>\n6. Cache file deleted after transcription\n\nSupported:\n• Telegram OGG/OPUS voice messages\n• WhatsApp OGG voice notes (via Twilio)\n• Any audio format supported by Whisper\n\nRequires: OpenAI API key (for Whisper) or on-device STT model",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        ),
        GuideStep(10, "👥", "Group Chat Support",
            "ZeroClaw only responds in group chats when @mentioned or a command is used. Prevents the bot from flooding group conversations.",
            "Response triggers:\n• @YourBotName mention in the message\n• /ask, /ai, /zeroclaw commands\n• Any /command\n• Replying to a previous bot message\n\nIn private DMs: always responds.\nIn groups: only responds to triggers above.\n\nThe @mention is stripped before sending to the LLM:\n'@mybot what is the weather?' → 'what is the weather?'\n\nGroup context added to system prompt:\n'You are in a group chat on Telegram. Be concise.'\n\nTelegram group chat IDs are negative numbers — auto-detected.",
            badgeColor = Color(0xFF4CAF50),
            isNew = true
        )
    )
)

// ─── NullClaw-Inspired Features Guide ────────────────────────────────────────

val NULLCLAW_GUIDE = GuideSection(
    id = "nullclaw",
    label = "Advanced Features",
    emoji = "🚀",
    accentColor = Color(0xFFFF6D00),
    intro = "Advanced features from NullClaw: 1000+ app integrations, multi-agent tools, MCP servers, memory diversity, A2A protocol, and more. Most require setup — enable them in Settings when ready.",
    steps = listOf(
        GuideStep(1, "🔗", "Composio — 1000+ App Integrations",
            "Connect to GitHub, Gmail, Jira, Notion, Salesforce, and 250+ other apps with one API key. No need to set up OAuth per-app — Composio handles it all.",
            "How to use:\n1. Go to composio.dev and create a free account\n2. Get your API key from the dashboard\n3. In ZeroClaw Settings → Advanced → Composio API Key, paste it\n4. Enable the Composio tool in Settings → AI Tools\n5. In chat: 'list apps' → 'list actions for github' → 'execute create issue'\n\nExample commands:\n• 'Create a GitHub issue titled Bug Report'\n• 'Send me an email via Gmail'\n• 'Create a Jira ticket'\n\nFree tier: 100 actions/month. Upgrade for more.",
            "composio.dev",
            isNew = true
        ),
        GuideStep(2, "🤖", "Delegate Tool — Ask a Specialist Agent",
            "Delegate a task to a named AI persona. Say 'delegate to coder: write a Python function'. The specialist agent handles it and returns the answer.",
            "Built-in agents you can delegate to:\n• coder — senior engineer, clean code, tests\n• analyst — data-driven, precise, structured\n• creative — vivid writing, storytelling\n• tutor — step-by-step, patient explanations\n• brief — ultra-concise bullet points\n\nHow to use:\n1. Enable 'Delegate Tool' in Settings → AI Tools\n2. In chat: 'delegate to coder: write a function to...'\n\nThe delegate tool blocks until the specialist completes (max 2 minutes).\nNote: Sub-agents cannot delegate further to prevent loops.",
            isNew = true
        ),
        GuideStep(3, "⚡", "Spawn Tool — Background Parallel Agents",
            "Spawn agents for background tasks and get results later. Unlike delegate (which waits), spawn returns a task ID immediately so you can do other things.",
            "How to use:\n1. Enable 'Spawn Tool' in Settings → AI Tools\n2. In chat: 'spawn task: research quantum computing'\n   → Returns: 'Task ID: agent_1 — RUNNING'\n3. Later: 'spawn status task_id=agent_1'\n   → Shows status + result when done\n4. 'spawn collect task_id=agent_1'\n   → Returns full result\n\nUse spawn for:\n• Long research tasks you don't want to wait for\n• Running multiple tasks in parallel ('spawn 3 agents to research...')\n• Background monitoring or analysis",
            isNew = true
        ),
        GuideStep(4, "📨", "Message Tool — Proactive Notifications",
            "Send messages to any connected channel from cron jobs or agents — without waiting for user input. Perfect for morning digests, alerts, and scheduled reports.",
            "How to use:\n1. Enable 'Message Tool' in Settings → AI Tools\n2. Create a cron job that uses message tool:\n   • 'every morning at 8am, search news and send me a summary on telegram'\n   • ZeroClaw will find your chat ID automatically from recent messages\n\nExamples:\n• Morning news digest to Telegram\n• Stock price alert to Discord\n• Daily weather to Slack\n\nRequires: Message Tool enabled + target channel connected + correct chat ID.",
            isNew = true
        ),
        GuideStep(5, "🔌", "MCP Client — External Tool Servers",
            "Connect to any MCP (Model Context Protocol) server to gain extra tools. MCP is an open standard — hundreds of servers exist for GitHub, databases, file systems, and more.",
            "What is MCP?\nMCP is a protocol that lets AI models call external tools via JSON-RPC 2.0. Think of it as a plugin system for AI.\n\nHow to set up:\n1. Run an MCP server (e.g. npx @modelcontextprotocol/server-github)\n2. Expose it with ngrok or Cloudflare: ngrok http 3001\n3. In Settings → Advanced → MCP Server URL, paste the public URL\n4. Enable MCP in Settings → Advanced\n5. ZeroClaw auto-discovers tools from the server\n\nDiscovered tools show up as 'mcp_[server]_[tool]' in the tools list.",
            isNew = true
        ),
        GuideStep(6, "🎯", "Smart Memory Search",
            "Memory search now uses two improvements: MMR diversity (avoids showing 5 near-identical results) and Adaptive Retrieval (auto-picks keyword/vector/hybrid based on your query).",
            "MMR (Maximal Marginal Relevance):\n• After finding the top results, MMR filters out redundant ones\n• You get a diverse set of relevant memories, not 5 near-duplicates\n• Diversity vs relevance balance: configurable (default 50/50)\n\nAdaptive Retrieval:\n• Short technical queries → keyword search (faster)\n• Long natural language questions → vector search (semantic)\n• Mixed queries → hybrid search (both)\n• Auto-selected based on query analysis — no user action needed\n\nResult: Better memory recall, faster responses for short queries.",
            isNew = true
        ),
        GuideStep(7, "💾", "Memory Cache & Hygiene",
            "Semantic cache avoids redundant LLM calls for similar questions. Memory hygiene auto-archives old memories to keep the database fast.",
            "Semantic Cache:\n• Before calling the AI, checks if a similar question was asked recently\n• 80% similarity threshold (cosine) for cache hit\n• Saves API costs for repeated or similar questions\n• Cache holds up to 100 entries, 30-minute TTL\n\nMemory Hygiene (runs every 12 hours):\n• Memories > 7 days old → archived\n• Archived memories > 30 days old → permanently deleted\n• Pinned memories are NEVER deleted\n• Low battery → hygiene run is postponed",
            isNew = true
        ),
        GuideStep(8, "🌐", "A2A Protocol — Agent Networks",
            "A2A (Agent-to-Agent) lets other AI agents call your ZeroClaw agent as a service. Your agent gets a public address that other agents can discover and send tasks to.",
            "What is A2A?\nGoogle's open standard for AI agents to communicate. Your agent exposes:\n• GET /a2a/agent-card.json — describes capabilities\n• POST /a2a — receives task requests, returns results async\n\nHow to enable:\n1. Enable Web Chat in Settings (required for the HTTP server)\n2. Enable A2A in Settings → Advanced → A2A Protocol\n3. Start the service — your agent is now discoverable\n4. Other agents find you via: [your-tunnel-url]/a2a/agent-card.json\n\nDisabled by default — only enable if you want external agent access.",
            isNew = true
        ),
        GuideStep(9, "🧭", "Hint-Based Provider Routing",
            "Prefix your message with a hint to route to the best model for that task. The AI automatically uses the most capable model for what you need.",
            "Available hints (just add to start of message):\n• hint:reasoning → Claude Sonnet/Opus or o1 for complex analysis\n• hint:vision → GPT-4o or Gemini Vision for images\n• hint:fast → Haiku or GPT-4o-mini for quick answers\n• hint:code → Claude or GPT-4 for programming\n• hint:creative → GPT-4o or Claude for writing/art\n• hint:long → Gemini for very long documents\n\nExamples:\n• 'hint:reasoning Explain the proof of the Riemann hypothesis'\n• 'hint:code Write a React hook for infinite scroll'\n• 'hint:fast What's 15% of 240?'\n\nHints only work when you have keys from multiple providers.",
            isNew = true
        ),
        GuideStep(10, "🎭", "Agent Identity (AIEOS v1.1)",
            "Give your AI agent a defined personality: MBTI type, OCEAN traits, catchphrases, and forbidden phrases. The identity shapes every response automatically.",
            "Identity components:\n• MBTI: 16 personality types (default: INTJ — strategic, direct)\n• OCEAN: 5 personality dimensions (0-100 sliders)\n  - Openness: curiosity and creativity\n  - Conscientiousness: organization and reliability\n  - Extraversion: chattiness vs. brevity\n  - Agreeableness: helpfulness vs. bluntness\n  - Neuroticism: calm vs. anxious\n• Catchphrases: phrases the agent uses naturally\n• Forbidden words: phrases the agent never says\n\nDefault: INTJ (Mastermind) — direct, precise, analytical.\nCustomize via agent profile settings.",
            isNew = true
        ),
        GuideStep(11, "📲", "Pushover Notifications",
            "Send push notifications from ZeroClaw to any of your devices (iOS, Android, desktop) via Pushover. Great for alerts from cron jobs.",
            "Setup (one-time):\n1. Create account at pushover.net (free tier: 10,000 messages/month)\n2. Download Pushover app on your target device\n3. Get your User Key from pushover.net/dashboard\n4. Create an Application at pushover.net/apps — get the API Token\n5. In Settings → Pushover → paste both keys\n6. Enable Pushover Tool in Settings → AI Tools\n\nUsage:\n• 'send me a pushover notification when task completes'\n• In cron: 'notify me via pushover: your daily summary is ready'\n\nPriority levels: quiet (−1), normal (0), high (1), emergency (2, requires ack)",
            isNew = true
        ),
        GuideStep(12, "📋", "Audit Log",
            "Every tool action is logged to a tamper-evident JSON Lines file. Review exactly what tools ran, when, and what they returned.",
            "Audit log location:\n Files/zeroclaw/audit_YYYY-MM-DD.jsonl\n\nEach entry includes:\n• Timestamp (UTC)\n• Tool name\n• Arguments used\n• Success/failure\n• Result summary (first 200 chars)\n• Duration in milliseconds\n• Hash chain for tamper detection\n\nLog rotation: one file per day, 30 days retained.\n\nEnabled by default (low overhead).\nUse in Settings → Behavior → Audit Log to disable.\n\nRead log in chat: 'show me today's tool audit log'",
            isNew = true
        )
    )
)

val ALL_GUIDE_SECTIONS = listOf(HOW_IT_WORKS, TELEGRAM_GUIDE, WHATSAPP_GUIDE, AI_TOOLS_GUIDE, ADVANCED_AI_GUIDE, MEMORY_GUIDE, INFRA_GUIDE, CONFIG_UX_GUIDE, NULLCLAW_GUIDE, OTHER_APPS_GUIDE)
