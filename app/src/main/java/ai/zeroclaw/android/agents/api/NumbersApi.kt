package ai.zeroclaw.android.agents.api

/**
 * Numbers API — Free random number/date/year facts.
 * No key required. No rate limit documented.
 * Docs: http://numbersapi.com/
 *
 * Bonus: DiceBear Avatars API — Free avatar generation.
 * Docs: https://www.dicebear.com/
 */
class NumbersApi : ApiDataSource {

    override val sourceId = "numbers_facts"
    override val displayName = "Numbers API (Free)"
    override val rateLimit = ApiRateLimit.unlimited("Unlimited")

    override suspend fun fetch(params: Map<String, String>): ApiResult {
        return try {
            val type = params["type"] ?: "trivia" // trivia, math, date, year
            val number = params["query"] ?: "random"

            val url = "http://numbersapi.com/$number/$type"
            val (code, body) = httpGet(url)
            if (code != 200) return ApiResult.fail("Numbers API HTTP $code")

            ApiResult.ok("Number Fact: $body")
        } catch (e: Exception) {
            ApiResult.fail("Numbers API error: ${e.message}")
        }
    }
}
