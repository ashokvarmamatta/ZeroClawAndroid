package ai.zeroclaw.android.agents.api

import ai.zeroclaw.android.service.ZeroClawService

/**
 * Phase 166 — Registry of all free API data sources.
 *
 * Maps template IDs / source IDs to their ApiDataSource implementation.
 * When an agent has an apiSource, WebScraperAgent uses this registry
 * to fetch data directly instead of web scraping + LLM extraction.
 */
object FreeApiRegistry {

    private val sources: Map<String, ApiDataSource> by lazy {
        listOf(
            CoinGeckoApi(),
            CoinCapApi(),
            MetalsLiveApi(),
            ExchangeRateApi(),
            UsgsEarthquakeApi(),
            MfApiIndiaApi(),
            GitHubTrendingApi(),
            WttrWeatherApi(),
            FootballDataApi(),
            NumbersApi()
        ).associateBy { it.sourceId }
    }

    /** Map template IDs to API source IDs */
    private val templateToSource = mapOf(
        "tpl_crypto_tracker" to "coingecko",
        "tpl_gold_tracker" to "metals_live",
        "tpl_forex" to "exchangerate",
        "tpl_earthquake" to "usgs_earthquake",
        "tpl_mutual_fund" to "mfapi_india",
        "tpl_github_trending" to "github_trending",
        "tpl_weather" to "wttr_weather",
        "tpl_sports" to "football_data"
    )

    /** Get data source by source ID */
    fun getSource(sourceId: String): ApiDataSource? = sources[sourceId]

    /** Get data source for a template ID */
    fun getSourceForTemplate(templateId: String): ApiDataSource? {
        val sourceId = templateToSource[templateId] ?: return null
        return sources[sourceId]
    }

    /** Get all registered sources */
    fun allSources(): List<ApiDataSource> = sources.values.toList()

    /** Get all template IDs that have a direct API source */
    fun templatesWithApi(): Set<String> = templateToSource.keys

    /**
     * Try fetching via direct API. Returns null if no API source is available
     * for the given template or if the API is rate-limited.
     */
    suspend fun tryFetch(templateId: String?, params: Map<String, String>): ApiResult? {
        if (templateId == null) return null

        val source = getSourceForTemplate(templateId) ?: return null

        // Check rate limit
        if (!ApiRateLimiter.isAllowed(source.sourceId, source.rateLimit)) {
            val retryMs = ApiRateLimiter.retryAfterMs(source.sourceId, source.rateLimit)
            val retrySec = (retryMs / 1000).coerceAtLeast(1)
            ZeroClawService.log("API_RATE: ${source.sourceId} rate-limited, retry in ${retrySec}s")
            return null // caller will fall back to web scraping
        }

        ZeroClawService.log("FREE_API: fetching via ${source.displayName} for template $templateId")
        val result = source.fetch(params)

        if (result.success) {
            ApiRateLimiter.record(source.sourceId)
            ZeroClawService.log("FREE_API: ${source.sourceId} OK (${result.content.length} chars)")
        } else {
            ZeroClawService.log("FREE_API: ${source.sourceId} failed: ${result.error}")
        }

        return result
    }
}
