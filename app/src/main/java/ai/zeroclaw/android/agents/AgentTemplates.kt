package ai.zeroclaw.android.agents

/**
 * AgentTemplate — a preset agent configuration that users can one-tap activate.
 * Templates have default URLs, prompts, and intervals; some allow user customization
 * (e.g. country, company name, job title).
 *
 * {query} placeholder in url and extractPrompt is replaced with the user's
 * selected sub-categories or custom input at agent creation time.
 */
data class AgentTemplate(
    val id: String,
    val name: String,
    val emoji: String,
    val category: String,
    val description: String,
    val url: String,
    val extractPrompt: String,
    val intervalMinutes: Int,
    val onlyOnChange: Boolean = true,
    /** If non-empty, the user can pick sub-categories (e.g. Tech, Gold, Politics) */
    val subCategories: List<String> = emptyList(),
    /** Placeholder text for user-customizable field (e.g. "Enter company name") */
    val customFieldHint: String = "",
    /** If true, URL has {query} placeholder replaced with user input */
    val needsUserInput: Boolean = false,
    /** Phase 166: If set, use direct free API instead of web scraping. Maps to FreeApiRegistry source. */
    val apiSource: String? = null,
    /** Phase 166: Human-readable API rate limit note for the UI */
    val apiRateNote: String? = null
)

/** News sub-categories — used by the "Latest News" template edit mode */
val NEWS_CATEGORIES = listOf(
    "Technology", "Gold & Commodities", "Politics", "Sports", "Entertainment",
    "Business & Finance", "Science", "Health & Medicine", "World News",
    "Crypto & Blockchain", "Climate & Environment", "Education",
    "Automobiles", "Real Estate", "Startups", "AI & Machine Learning",
    "Gaming", "Space & Astronomy", "Food & Agriculture", "Travel",
    "Cybersecurity", "Energy & Oil", "Defense & Military", "Legal & Law"
)

/** All default agent templates */
val AGENT_TEMPLATES: List<AgentTemplate> = listOf(

    // ── 1. Latest News ──────────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_latest_news",
        name = "Latest News",
        emoji = "📰",
        category = "News",
        description = "Get breaking news headlines from top sources",
        url = "https://news.google.com/rss/search?q={query}",
        extractPrompt = "Extract the top 10 latest {query} news headlines with brief summaries. Only include news specifically about {query}. Format as numbered list: headline + 1-line summary each.",
        intervalMinutes = 60,
        subCategories = NEWS_CATEGORIES
    ),

    // ── 2. Gold & Commodity Tracker ─────────────────────────────────────
    AgentTemplate(
        id = "tpl_gold_tracker",
        name = "Gold Price Tracker",
        emoji = "🥇",
        category = "Finance",
        description = "Track live gold, silver, copper, platinum & commodity prices",
        url = "https://www.google.com/search?q={query}+price+today",
        extractPrompt = "Extract current {query} price from the provided content. Show: current price, today's change (amount and %), open, high, low. If multiple units are available (per ounce, per gram, per kg) show all. Format cleanly as a price report.",
        intervalMinutes = 120,
        subCategories = listOf(
            "Gold price in India INR per gram",
            "Gold price in India INR per 10 grams",
            "Gold price USD per ounce",
            "Silver price in India INR per kg",
            "Silver price USD per ounce",
            "Copper price USD per pound",
            "Platinum price USD per ounce",
            "Palladium price USD per ounce",
            "Crude Oil WTI price USD",
            "Crude Oil Brent price USD",
            "Natural Gas price USD",
            "Aluminium price USD per ton",
            "Zinc price USD per ton",
            "Nickel price USD per ton",
            "Gold price in UK GBP per gram",
            "Gold price in Europe EUR per gram",
            "Gold price in UAE AED per gram"
        ),
        apiSource = "metals_live",
        apiRateNote = "Metals.live: free, no key needed (USD spot prices)"
    ),

    // ── 3. Stock/Trade Tracker ──────────────────────────────────────────
    AgentTemplate(
        id = "tpl_trade_tracker",
        name = "Stock & Trade Tracker",
        emoji = "📈",
        category = "Finance",
        description = "Track stock indices and market data for your country",
        url = "https://www.google.com/search?q={query}+stock+index+price+today",
        extractPrompt = "Extract current data for {query}. Show: index/stock name, current value, today's change (points and %), open, high, low. Format as clean readable list.",
        intervalMinutes = 60,
        needsUserInput = true,
        customFieldHint = "Enter index or stock (e.g. NIFTY 50, SENSEX, S&P 500, NASDAQ)",
        subCategories = listOf(
            "NIFTY 50", "SENSEX", "S&P 500", "NASDAQ", "DOW JONES",
            "FTSE 100", "DAX", "NIKKEI 225", "HANG SENG", "ASX 200",
            "CAC 40", "KOSPI", "SHANGHAI", "IBOVESPA", "TSX"
        )
    ),

    // ── 4. Jobs Tracker ─────────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_jobs_tracker",
        name = "Jobs Tracker",
        emoji = "💼",
        category = "Career",
        description = "Find latest job postings from Naukri, Indeed, LinkedIn & more",
        url = "https://www.google.com/search?q=latest+jobs+{query}",
        extractPrompt = "Extract the latest {query} job listings. For each job show: Job Title, Company, Location, and Source (Naukri/Indeed/LinkedIn etc). List at least 10 jobs. Format as clean numbered list.",
        intervalMinutes = 360,
        needsUserInput = true,
        customFieldHint = "Enter job title or skill (e.g. Android Developer, Data Scientist)"
    ),

    // ── 5. Company News Tracker ─────────────────────────────────────────
    AgentTemplate(
        id = "tpl_company_news",
        name = "Company News Tracker",
        emoji = "🏢",
        category = "Business",
        description = "Track news from specific companies (Apple, Samsung, etc.)",
        url = "https://www.google.com/search?q={query}+latest+news&tbm=nws",
        extractPrompt = "Extract the latest news specifically about {query}. For each item show: Headline, Source, Date, and 1-line summary. List top 10 news items. Format cleanly.",
        intervalMinutes = 180,
        needsUserInput = true,
        customFieldHint = "Enter company names (e.g. Apple, Samsung, Tesla)",
        subCategories = listOf(
            "Apple", "Google", "Microsoft", "Amazon", "Tesla", "Samsung",
            "Meta", "Netflix", "NVIDIA", "Intel", "AMD", "Sony",
            "Toyota", "Reliance", "TCS", "Infosys", "Wipro", "HCL"
        )
    ),

    // ── 6. Crypto Tracker ───────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_crypto_tracker",
        name = "Crypto Price Tracker",
        emoji = "₿",
        category = "Finance",
        description = "Track Bitcoin, Ethereum, and top cryptocurrency prices",
        url = "https://www.google.com/finance/quote/BTC-USD",
        extractPrompt = "Extract current prices for top cryptocurrencies: Bitcoin, Ethereum, BNB, Solana, XRP. Show price in USD and 24h % change. Format as clean list.",
        intervalMinutes = 120,
        apiSource = "coingecko",
        apiRateNote = "CoinGecko free API: 30 req/min, no key needed"
    ),

    // ── 7. Weather Updates ──────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_weather",
        name = "Daily Weather",
        emoji = "🌤️",
        category = "Utility",
        description = "Get daily weather forecast for your city",
        url = "https://wttr.in/{query}?format=3",
        extractPrompt = "Provide today's weather for {query}: temperature, conditions, humidity, and rain chance. Include 3-day forecast if available.",
        intervalMinutes = 360,
        needsUserInput = true,
        customFieldHint = "Enter city name (e.g. Hyderabad, New York, London)",
        onlyOnChange = false,
        apiSource = "wttr_weather",
        apiRateNote = "wttr.in: unlimited, no key needed"
    ),

    // ── 8. Sports Scores ────────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_sports",
        name = "Sports Live Scores",
        emoji = "⚽",
        category = "Sports",
        description = "Track live scores for cricket, football, and more",
        url = "https://www.google.com/search?q={query}+live+scores+results+today",
        extractPrompt = "Extract today's live and recent {query} scores only. Show: Teams, Score, Status (Live/Finished), Competition/League name. Do NOT include other sports — only {query}. Format as clean list.",
        intervalMinutes = 30,
        onlyOnChange = false,
        subCategories = listOf("Cricket", "Football", "Basketball", "Tennis", "F1", "NFL", "IPL", "Premier League", "Champions League", "NBA"),
        apiSource = "football_data",
        apiRateNote = "Football-Data.org: 10 req/min, free tier"
    ),

    // ── 9. Anime & Manga Tracker ────────────────────────────────────────
    AgentTemplate(
        id = "tpl_anime",
        name = "Anime & Manga Updates",
        emoji = "🎌",
        category = "Entertainment",
        description = "Track new anime releases, episodes, and manga chapters",
        url = "https://myanimelist.net/anime/season",
        extractPrompt = "Extract currently airing anime this season. For each: Title, Episode count, Genre, Rating. List top 15. Format as numbered list.",
        intervalMinutes = 720
    ),

    // ── 10. Tech Product Launches ───────────────────────────────────────
    AgentTemplate(
        id = "tpl_tech_launches",
        name = "Tech Product Launches",
        emoji = "📱",
        category = "Technology",
        description = "Track latest phone, laptop, and gadget launches",
        url = "https://www.google.com/search?q=latest+tech+product+launches+this+week",
        extractPrompt = "Extract latest tech product launches and announcements. For each: Product Name, Brand, Key Specs, Price (if available), Launch Date. List top 10.",
        intervalMinutes = 720
    ),

    // ── 11. Currency Exchange ───────────────────────────────────────────
    AgentTemplate(
        id = "tpl_forex",
        name = "Currency Exchange Rates",
        emoji = "💱",
        category = "Finance",
        description = "Track forex rates for major currency pairs",
        url = "https://www.google.com/search?q={query}+exchange+rate+today",
        extractPrompt = "Extract current exchange rate for {query}. Show: rate, daily change %, 1-week trend. Format cleanly.",
        intervalMinutes = 240,
        subCategories = listOf("USD/INR", "EUR/INR", "GBP/INR", "USD/EUR", "USD/JPY", "AUD/USD", "USD/CAD", "CHF/USD"),
        apiSource = "exchangerate",
        apiRateNote = "ExchangeRate-API: ~50 req/day free, no key needed"
    ),

    // ── 12. Real Estate Tracker ─────────────────────────────────────────
    AgentTemplate(
        id = "tpl_real_estate",
        name = "Real Estate Tracker",
        emoji = "🏠",
        category = "Finance",
        description = "Track property prices and real estate news",
        url = "https://www.google.com/search?q={query}+real+estate+prices+latest",
        extractPrompt = "Extract latest real estate news and property trends for {query}. Include: average prices, market trend (up/down), notable developments. Format cleanly.",
        intervalMinutes = 1440,
        needsUserInput = true,
        customFieldHint = "Enter city or area (e.g. Hyderabad, Mumbai, Bangalore)"
    ),

    // ── 13. GitHub Trending ─────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_github_trending",
        name = "GitHub Trending",
        emoji = "🐙",
        category = "Technology",
        description = "Track trending repositories on GitHub",
        url = "https://github.com/trending",
        extractPrompt = "Extract top 10 trending GitHub repositories today. For each: Repo name, Language, Stars gained today, Short description. Format as numbered list.",
        intervalMinutes = 720,
        apiSource = "github_trending",
        apiRateNote = "GitHub API: 60 req/hr unauthenticated, no key needed"
    ),

    // ── 14. Movie & TV Releases ─────────────────────────────────────────
    AgentTemplate(
        id = "tpl_movies",
        name = "Movie & TV Releases",
        emoji = "🎬",
        category = "Entertainment",
        description = "Track new movie and TV show releases",
        url = "https://www.google.com/search?q=new+movies+and+tv+shows+releasing+this+week",
        extractPrompt = "Extract latest movie and TV show releases this week. For each: Title, Platform (Netflix/Prime/Theater), Genre, Release Date. List top 10.",
        intervalMinutes = 1440
    ),

    // ── 15. Fuel Price Tracker ──────────────────────────────────────────
    AgentTemplate(
        id = "tpl_fuel_price",
        name = "Fuel Price Tracker",
        emoji = "⛽",
        category = "Utility",
        description = "Track daily petrol, diesel, and gas prices",
        url = "https://www.google.com/search?q={query}+petrol+diesel+price+today",
        extractPrompt = "Extract today's fuel prices in {query}: Petrol, Diesel, CNG (if available). Show price per litre and change from yesterday. Format cleanly.",
        intervalMinutes = 1440,
        needsUserInput = true,
        customFieldHint = "Enter city (e.g. Hyderabad, Delhi, Mumbai)"
    ),

    // ── 16. AI & ML News ────────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_ai_news",
        name = "AI & ML News",
        emoji = "🧠",
        category = "Technology",
        description = "Track latest developments in AI and machine learning",
        url = "https://www.google.com/search?q=artificial+intelligence+machine+learning+latest+news&tbm=nws",
        extractPrompt = "Extract top 10 latest AI and machine learning news. For each: Headline, Source, 1-line summary. Focus on breakthroughs, new models, and industry updates.",
        intervalMinutes = 360
    ),

    // ── 17. IPO Tracker ─────────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_ipo_tracker",
        name = "IPO Tracker",
        emoji = "🔔",
        category = "Finance",
        description = "Track upcoming and recent IPOs",
        url = "https://www.google.com/search?q=upcoming+IPO+this+week+latest",
        extractPrompt = "Extract upcoming and recent IPOs. For each: Company Name, Issue Price, Open/Close Date, Listing Date, GMP (if available), Subscription Status. Format as clean list.",
        intervalMinutes = 720
    ),

    // ── 18. Earthquake & Disaster Alerts ────────────────────────────────
    AgentTemplate(
        id = "tpl_earthquake",
        name = "Earthquake & Disaster Alerts",
        emoji = "🌍",
        category = "Safety",
        description = "Track earthquakes and natural disaster alerts worldwide",
        url = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_week.geojson",
        extractPrompt = "Extract significant earthquakes from the past week. For each: Magnitude, Location, Depth, Date/Time. List all significant events. Format cleanly.",
        intervalMinutes = 360,
        onlyOnChange = false,
        apiSource = "usgs_earthquake",
        apiRateNote = "USGS: unlimited, public government API, no key needed"
    ),

    // ── 19. E-commerce Deal Tracker ─────────────────────────────────────
    AgentTemplate(
        id = "tpl_deals",
        name = "Deal & Discount Tracker",
        emoji = "🏷️",
        category = "Shopping",
        description = "Track best deals on Amazon, Flipkart, and more",
        url = "https://www.google.com/search?q={query}+best+deals+offers+discount+today",
        extractPrompt = "Extract best {query} deals and discounts available today. For each: Product, Original Price, Deal Price, Discount %, Store. List top 10 deals. Format cleanly.",
        intervalMinutes = 720,
        needsUserInput = true,
        customFieldHint = "Enter product category (e.g. Smartphones, Laptops, Electronics)"
    ),

    // ── 20. Exam & Admission Updates ────────────────────────────────────
    AgentTemplate(
        id = "tpl_exams",
        name = "Exam & Admission Updates",
        emoji = "🎓",
        category = "Education",
        description = "Track exam results, admit cards, and admission notifications",
        url = "https://www.google.com/search?q={query}+exam+result+admit+card+latest",
        extractPrompt = "Extract latest {query} exam and admission updates. For each: Exam Name, Type (Result/Admit Card/Registration), Date, Official Website. List top 10.",
        intervalMinutes = 720,
        needsUserInput = true,
        customFieldHint = "Enter exam or board (e.g. JEE, NEET, UPSC, SSC, GATE)"
    ),

    // ── 21. YouTube Trending ────────────────────────────────────────────
    AgentTemplate(
        id = "tpl_youtube_trending",
        name = "YouTube Trending",
        emoji = "▶️",
        category = "Entertainment",
        description = "Track trending videos on YouTube",
        url = "https://www.google.com/search?q=youtube+trending+videos+today",
        extractPrompt = "Extract top 10 trending YouTube videos today. For each: Title, Channel, Views, Duration (if available). Format as numbered list.",
        intervalMinutes = 720
    ),

    // ── 22. Mutual Fund & SIP Tracker ───────────────────────────────────
    AgentTemplate(
        id = "tpl_mutual_fund",
        name = "Mutual Fund NAV Tracker",
        emoji = "📊",
        category = "Finance",
        description = "Track NAV and returns of top mutual funds",
        url = "https://www.google.com/search?q=top+mutual+funds+NAV+today+india",
        extractPrompt = "Extract NAV and returns for top mutual funds. For each: Fund Name, NAV, 1-year Return %, Category. List top 10 performing funds. Format cleanly.",
        intervalMinutes = 1440,
        apiSource = "mfapi_india",
        apiRateNote = "MFAPI.in: unlimited, free, India MF data"
    ),

    // ── 23. Flight Price Tracker ────────────────────────────────────────
    AgentTemplate(
        id = "tpl_flights",
        name = "Flight Price Tracker",
        emoji = "✈️",
        category = "Travel",
        description = "Track flight prices for your preferred routes",
        url = "https://www.google.com/search?q=cheap+flights+{query}",
        extractPrompt = "Extract cheapest flight options for {query}. For each: Airline, Price, Duration, Stops, Departure Time. List top 5 cheapest options. Format cleanly.",
        intervalMinutes = 720,
        needsUserInput = true,
        customFieldHint = "Enter route (e.g. Hyderabad to Delhi, Mumbai to Bangalore)"
    ),

    // ── 24. Government Schemes Tracker ──────────────────────────────────
    AgentTemplate(
        id = "tpl_govt_schemes",
        name = "Government Schemes",
        emoji = "🏛️",
        category = "Government",
        description = "Track latest government schemes and welfare programs",
        url = "https://www.google.com/search?q=latest+government+schemes+{query}+2026",
        extractPrompt = "Extract latest government schemes and programs in {query}. For each: Scheme Name, Benefits, Eligibility, How to Apply. List top 10. Format cleanly.",
        intervalMinutes = 1440,
        needsUserInput = true,
        customFieldHint = "Enter country or state (e.g. India, Telangana, Maharashtra)"
    ),

    // ── 25. Startup Funding Tracker ─────────────────────────────────────
    AgentTemplate(
        id = "tpl_startup_funding",
        name = "Startup Funding Tracker",
        emoji = "🚀",
        category = "Business",
        description = "Track latest startup funding rounds and acquisitions",
        url = "https://www.google.com/search?q=latest+startup+funding+rounds+this+week&tbm=nws",
        extractPrompt = "Extract latest startup funding news. For each: Company, Amount Raised, Round (Seed/Series A/B/C), Investors, Sector. List top 10. Format cleanly.",
        intervalMinutes = 720
    )
)

/** Get templates grouped by category */
fun getTemplatesByCategory(): Map<String, List<AgentTemplate>> =
    AGENT_TEMPLATES.groupBy { it.category }

/** Get unique categories */
fun getTemplateCategories(): List<String> =
    AGENT_TEMPLATES.map { it.category }.distinct()
