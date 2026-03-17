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
    FeatureItem("📱", "Offline AI Models",
        "Run AI models directly on your device using MediaPipe GenAI — no internet or API key needed. Load .bin models from storage."),
    FeatureItem("🔑", "API Key Manager",
        "Add, reorder, test, and manage keys for OpenAI, Anthropic, Google Gemini, OpenRouter, and Ollama. Drag to set priority order."),
    FeatureItem("🔧", "AI Tools (28 built-in)",
        "Web Search, Web Fetch, Memory, PDF Reader, Image Analysis, Scheduled Tasks, Status, GitHub, Notion, Email, Weather, Summarize, Translate, Image Gen, Speech-to-Text, Text-to-Speech, Calendar, Contacts, Location, Calculator, RSS Feed, QR Code, File Manager, Clipboard, Spotify, Smart Home, Brave Search, and Bookmarks. Toggle each on/off in Settings."),
    FeatureItem("🔍", "Google Search Grounding (Gemini)",
        "Enable per-key Google Search grounding for Gemini API calls. Replies include real-time web info — same as the Gemini app."),
    FeatureItem("🧠", "Advanced AI (8 features)",
        "Custom prompts, streaming responses, multi-agent system, agent profiles (6 personas), workflow engine, tool loop detection, thinking mode, and auto-summarization of long conversations."),
    FeatureItem("🔋", "Battery Optimized",
        "Smart persistence with boot auto-restart, wake locks, and foreground service — stays alive even on aggressive OEMs like Samsung and Xiaomi.")
)

val SUPPORTED_PROVIDERS = listOf(
    ProviderInfo("🟢", "OpenAI", "GPT-4o, GPT-4o-mini, o1, o3"),
    ProviderInfo("🟠", "Anthropic", "Claude Haiku, Sonnet, Opus"),
    ProviderInfo("🔵", "Google Gemini", "Gemini Pro, Flash, Ultra"),
    ProviderInfo("🟣", "OpenRouter", "400+ models from all providers"),
    ProviderInfo("⚫", "Ollama", "Local models, no API key needed"),
    ProviderInfo("📱", "Offline (MediaPipe)", "On-device .bin models, no internet")
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
            "You supply your own API keys — ZeroClaw never stores or proxies your keys anywhere outside your device. Supported providers:\n• OpenAI (GPT-4o, GPT-4o-mini)\n• Anthropic (Claude Haiku, Sonnet, Opus)\n• Google Gemini (Pro, Flash)\n• OpenRouter (400+ models)\n• Ollama (local, no API key needed)\n• Offline MediaPipe (on-device, no internet)",
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

val ALL_GUIDE_SECTIONS = listOf(HOW_IT_WORKS, TELEGRAM_GUIDE, WHATSAPP_GUIDE, AI_TOOLS_GUIDE, ADVANCED_AI_GUIDE, OTHER_APPS_GUIDE)
