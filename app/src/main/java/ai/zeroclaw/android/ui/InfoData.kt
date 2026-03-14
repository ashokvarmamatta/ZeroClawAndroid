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
    val badgeColor: Color = Color(0xFF1A1A2E)
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
    FeatureItem("✈️", "Telegram Bot",
        "Built-in Telegram bot integration with long-polling. Per-chat conversation history keeps context across messages."),
    FeatureItem("💬", "WhatsApp via Twilio",
        "Receive and reply to WhatsApp messages using Twilio's official API. Works with both sandbox and production numbers."),
    FeatureItem("🌐", "Cloudflare / ngrok Tunnel",
        "Expose your phone to the internet with one tap. Supports Cloudflare Tunnel (free, no account) and ngrok."),
    FeatureItem("📱", "Offline AI Models",
        "Run AI models directly on your device using MediaPipe GenAI — no internet or API key needed. Load .bin models from storage."),
    FeatureItem("🔑", "API Key Manager",
        "Add, reorder, test, and manage keys for OpenAI, Anthropic, Google Gemini, OpenRouter, and Ollama. Drag to set priority order."),
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
    label = "Other Apps",
    emoji = "🔌",
    accentColor = Color(0xFF7C4DFF),
    intro = "ZeroClaw's architecture supports any chat platform that can send HTTP webhooks or be polled via API. Here's how other platforms work:",
    steps = listOf(
        GuideStep(1, "🎮", "Discord Bot",
            "Discord has a full bot API — create a bot in Discord Developer Portal and connect it to ZeroClaw via a simple bridge script.",
            "Steps:\n1. Go to: https://discord.com/developers/applications\n2. New Application → Bot → Add Bot\n3. Copy Bot Token\n4. Enable Message Content Intent under Privileged Gateway Intents\n5. Invite bot to server via OAuth2 URL\n6. Run a tiny Python/Node bridge that receives Discord messages and forwards to ZeroClaw API\n\nDiscord support is planned as a native ZeroClaw channel — check the GitHub for updates.",
            "https://discord.com/developers/applications",
            badgeColor = Color(0xFF5865F2)
        ),
        GuideStep(2, "💼", "Slack Bot",
            "Slack has a rich API for bots. You can forward messages from a Slack workspace to ZeroClaw and reply automatically.",
            "Steps:\n1. Go to: https://api.slack.com/apps\n2. Create New App → From scratch\n3. Enable Event Subscriptions → Subscribe to message.channels\n4. Set Request URL to: YOUR_TUNNEL_URL/slack\n5. Install app to workspace and copy Bot Token\n\nUse the 'xoxb-' prefixed token in a bridge that forwards messages to ZeroClaw.",
            "https://api.slack.com/apps",
            badgeColor = Color(0xFF4A154B)
        ),
        GuideStep(3, "📡", "Signal (via Signal-CLI)",
            "Signal doesn't have an official bot API, but signal-cli is an open-source tool that lets you automate Signal messages.",
            "This is advanced and requires a dedicated phone number:\n1. Install signal-cli on a Termux/Linux device\n2. Register a new number with Signal\n3. Use signal-cli's JSON-RPC mode to receive messages\n4. Forward to ZeroClaw API and reply back\n\nWarning: Using automation with Signal may violate their ToS. Use a dedicated number, not your personal one.",
            "https://github.com/AsamK/signal-cli",
            badgeColor = Color(0xFF2C6BED)
        ),
        GuideStep(4, "📧", "Email (via SendGrid/Mailgun)",
            "You can connect email to ZeroClaw — inbound emails trigger the AI agent and replies are sent back via email.",
            "Using SendGrid Inbound Parse:\n1. Create SendGrid account (free tier available)\n2. Set up Inbound Parse webhook pointing to YOUR_TUNNEL_URL/email\n3. SendGrid forwards incoming emails as HTTP POST to your phone\n4. ZeroClaw processes and replies via SendGrid send API\n\nGreat for customer support automation.",
            "https://sendgrid.com/solutions/email-api/",
            badgeColor = Color(0xFF1A82E2)
        ),
        GuideStep(5, "🤖", "Custom Webhook (Any Platform)",
            "Any platform that can send HTTP POST requests can be connected to ZeroClaw as a custom channel.",
            "ZeroClaw's webhook server listens on port 8080. Send POST requests in this format:\n\nPOST YOUR_TUNNEL_URL/custom\nContent-Type: application/json\n{\n  \"from\": \"user_id\",\n  \"message\": \"Hello!\",\n  \"channel\": \"my_platform\"\n}\n\nThe response will contain the AI reply you can forward to the user.",
            "POST /custom { \"from\": \"id\", \"message\": \"text\" }",
            badgeColor = Color(0xFF7C4DFF)
        ),
        GuideStep(6, "🌐", "Web Chat Widget",
            "Embed a chat widget on any website that talks to your ZeroClaw agent running on your phone.",
            "ZeroClaw exposes a simple REST API:\n  GET  /health  — check if agent is alive\n  POST /chat    — send a message, get AI reply\n\nEmbed a JavaScript widget on your site that sends fetch() requests to your tunnel URL. Your phone handles all the AI processing — zero server costs.",
            "POST /chat { \"message\": \"Hello\" }",
            badgeColor = Color(0xFF7C4DFF)
        ),
        GuideStep(7, "📅", "Coming Soon in ZeroClaw",
            "The ZeroClaw GitHub roadmap includes native support for more channels beyond Telegram.",
            "Planned native channels (check github.com/zeroclaw-labs/zeroclaw):\n• Discord\n• Matrix / Element\n• IRC\n• Slack\n• LINE\n• WeChat (via unofficial bridge)\n\nWatch the repo and star it to get notified when new channels ship.",
            "https://github.com/zeroclaw-labs/zeroclaw",
            badgeColor = Color(0xFF7C4DFF)
        )
    )
)

// ─── All sections list ─────────────────────────────────────────────────────────

val ALL_GUIDE_SECTIONS = listOf(HOW_IT_WORKS, TELEGRAM_GUIDE, WHATSAPP_GUIDE, OTHER_APPS_GUIDE)
