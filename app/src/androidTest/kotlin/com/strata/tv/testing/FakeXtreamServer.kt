package com.strata.tv.testing

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** Fake Xtream provider serving only account and catalogue JSON endpoints. */
class FakeXtreamServer {
    private val server = MockWebServer()
    var authenticated: Boolean = true
    var expiryEpochSeconds: Long = System.currentTimeMillis() / 1000 + 86_400 * 30
    var liveCategoriesJson: String = "[]"
    var vodCategoriesJson: String = "[]"
    var seriesCategoriesJson: String = "[]"
    var liveStreamsJson: String = "[]"
    var vodStreamsJson: String = "[]"
    var seriesJson: String = "[]"
    var seriesInfoJson: String = """{"episodes":{}}"""

    fun start() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (!path.startsWith("/player_api.php")) return MockResponse().setResponseCode(404)
                val action = Regex("[?&]action=([^&]+)").find(path)?.groupValues?.get(1)
                val body = when (action) {
                    null -> playerApiBody()
                    "get_live_categories" -> liveCategoriesJson
                    "get_vod_categories" -> vodCategoriesJson
                    "get_series_categories" -> seriesCategoriesJson
                    "get_live_streams" -> liveStreamsJson
                    "get_vod_streams" -> vodStreamsJson
                    "get_series" -> seriesJson
                    "get_series_info" -> seriesInfoJson
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body)
            }
        }
    }
    fun shutdown() { runCatching { server.shutdown() } }
    fun host(): String = server.url("/").toString().trimEnd('/')
    private fun playerApiBody(): String {
        val auth = if (authenticated) 1 else 0
        return """
            {"user_info":{"username":"test","auth":$auth,"status":"Active",
            "exp_date":"$expiryEpochSeconds","active_cons":"1","max_connections":"2"}}
        """.trimIndent()
    }
}
