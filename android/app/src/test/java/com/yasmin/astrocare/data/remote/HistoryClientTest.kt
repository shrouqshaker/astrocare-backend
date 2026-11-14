package com.yasmin.astrocare.data.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class HistoryClientTest {

    @Test
    fun `fetch uses auth header limit and all history URLs and decodes arrays`() {
        withServer({ path ->
            when (path.substringAfterLast('/')) {
                "measurements" -> StubResponse(200, envelope("[$MEASUREMENT]", 1))
                "symptom-checkins" -> StubResponse(200, envelope("[$SYMPTOM_CHECKIN]", 1))
                "alerts" -> StubResponse(200, envelope("[$ALERT]", 1))
                else -> StubResponse(404, "{}")
            }
        }) { baseUrl, requests ->
            val snapshot = client().fetch(
                baseUrl = "$baseUrl/api/v1/",
                apiKey = "test-key",
                astronautId = "demo-astronaut-01",
            )

            assertEquals(listOf("measurement-1"), snapshot.measurements.map { it.id })
            assertEquals(listOf("checkin-1"), snapshot.symptomCheckins.map { it.id })
            assertEquals(listOf("alert-1"), snapshot.alerts.map { it.id })
            assertTrue(snapshot.limitedCollections.isEmpty())
            assertEquals(3, requests.size)
            requests.forEach { request ->
                assertEquals("test-key", request.apiKey)
                assertEquals("limit=1000", request.rawQuery)
                assertTrue(
                    request.path in setOf(
                        "/api/v1/astronauts/demo-astronaut-01/measurements",
                        "/api/v1/astronauts/demo-astronaut-01/symptom-checkins",
                        "/api/v1/astronauts/demo-astronaut-01/alerts",
                    ),
                )
            }
        }
    }

    @Test
    fun `host only base URL includes the api prefix`() {
        withServer({ StubResponse(200, envelope("[]", 0)) }) { baseUrl, requests ->
            client().fetch(baseUrl, "key", ASTRONAUT)
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.path.startsWith("/api/v1/astronauts/$ASTRONAUT/") })
        }
    }

    @Test
    fun `401 is surfaced as retrofit HttpException`() {
        withServer({ StubResponse(401, "{\"error\":\"UNAUTHORIZED\"}") }) { baseUrl, _ ->
            val error = org.junit.Assert.assertThrows(HttpException::class.java) {
                runBlocking {
                    client().fetch(baseUrl, "wrong-key", "demo-astronaut-01")
                }
            }
            assertEquals(401, error.code())
        }
    }

    @Test
    fun `missing response fields fail deserialization`() {
        listOf("{\"count\":0}", "{\"items\":[]}").forEach { malformedBody ->
            withServer({ StubResponse(200, malformedBody) }) { baseUrl, _ ->
                org.junit.Assert.assertThrows(SerializationException::class.java) {
                    runBlocking {
                        client().fetch(baseUrl, "key", "demo-astronaut-01")
                    }
                }
            }
        }
    }

    @Test
    fun `count mismatch is rejected`() {
        withServer({ StubResponse(200, envelope("[]", 1)) }) { baseUrl, _ ->
            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    client().fetch(baseUrl, "key", "demo-astronaut-01")
                }
            }
            assertTrue(error.message.orEmpty().contains("count"))
        }
    }

    @Test
    fun `exact limit is reported as potentially limited`() {
        val items = (0 until 1000).joinToString(",") { index ->
            """
            {
              "schemaVersion":"1.0",
              "id":"measurement-$index",
              "astronautId":"demo-astronaut-01",
              "metric":"heart_rate",
              "values":{"bpm":68},
              "measuredAt":"2026-10-31T07:15:00Z",
              "utcOffsetMinutes":0,
              "environment":"iss",
              "source":"synthetic",
              "isSynthetic":true,
              "createdAt":"2026-10-31T07:15:00Z"
            }
            """.trimIndent()
        }
        withServer({ path ->
            if (path.endsWith("/measurements")) {
                StubResponse(200, envelope("[$items]", 1000))
            } else {
                StubResponse(200, envelope("[]", 0))
            }
        }) { baseUrl, _ ->
            val snapshot = client().fetch(baseUrl, "key", "demo-astronaut-01")
            assertEquals(1000, snapshot.measurements.size)
            assertEquals(listOf("measurements"), snapshot.limitedCollections)
        }
    }

    @Test
    fun `empty collections return an unbounded empty snapshot`() {
        withServer({ StubResponse(200, envelope("[]", 0)) }) { baseUrl, _ ->
            val snapshot = client().fetch(baseUrl, "key", "demo-astronaut-01")
            assertTrue(snapshot.measurements.isEmpty())
            assertTrue(snapshot.symptomCheckins.isEmpty())
            assertTrue(snapshot.alerts.isEmpty())
            assertTrue(snapshot.limitedCollections.isEmpty())
        }
    }

    @Test
    fun `duplicate IDs are rejected case insensitively`() {
        val first = MEASUREMENT.replace("measurement-1", "same-id")
        val second = MEASUREMENT.replace("measurement-1", "SAME-ID")
        withServer({ path ->
            if (path.endsWith("/measurements")) {
                StubResponse(200, envelope("[$first,$second]", 2))
            } else {
                StubResponse(200, envelope("[]", 0))
            }
        }) { baseUrl, _ ->
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    client().fetch(baseUrl, "key", "demo-astronaut-01")
                }
            }
        }
    }

    @Test
    fun `base URL rejects paths userinfo query fragments and unsafe astronaut IDs`() {
        val client = client()
        assertThrowsIOException { runBlocking { client.fetch("http://localhost:1/other", "k", ASTRONAUT) } }
        assertThrowsIOException { runBlocking { client.fetch("http://user:pass@localhost:1", "k", ASTRONAUT) } }
        assertThrowsIOException { runBlocking { client.fetch("http://localhost:1?wrong=true", "k", ASTRONAUT) } }
        assertThrowsIOException { runBlocking { client.fetch("http://localhost:1#wrong", "k", ASTRONAUT) } }
        assertThrowsIOException { runBlocking { client.fetch("http://localhost:1", "k", "demo/astronaut") } }
    }

    private fun client(): HistoryClient = HistoryClient(
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
        okHttpClient = OkHttpClient.Builder().build(),
    )

    private fun withServer(
        responder: (path: String) -> StubResponse,
        block: suspend (baseUrl: String, requests: List<RequestRecord>) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<RequestRecord>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange: HttpExchange ->
            requests += RequestRecord(
                path = exchange.requestURI.path,
                rawQuery = exchange.requestURI.rawQuery.orEmpty(),
                apiKey = exchange.requestHeaders.getFirst("X-API-Key"),
            )
            val response = responder(exchange.requestURI.path)
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            runBlocking { block("http://127.0.0.1:${server.address.port}", requests) }
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun envelope(items: String, count: Int): String =
        "{\"items\":$items,\"count\":$count}"

    private fun assertThrowsIOException(block: () -> Unit) {
        org.junit.Assert.assertThrows(IOException::class.java) {
            block()
        }
    }

    private data class StubResponse(val status: Int, val body: String)

    private data class RequestRecord(
        val path: String,
        val rawQuery: String,
        val apiKey: String?,
    )

    private companion object {
        const val ASTRONAUT = "demo-astronaut-01"
        const val MEASUREMENT = """
            {
              "schemaVersion":"1.0",
              "id":"measurement-1",
              "astronautId":"demo-astronaut-01",
              "metric":"heart_rate",
              "values":{"bpm":68},
              "measuredAt":"2026-10-31T07:15:00Z",
              "utcOffsetMinutes":0,
              "environment":"iss",
              "source":"synthetic",
              "isSynthetic":true,
              "createdAt":"2026-10-31T07:15:00Z"
            }
        """
        const val SYMPTOM_CHECKIN = """
            {
              "schemaVersion":"1.0",
              "id":"checkin-1",
              "astronautId":"demo-astronaut-01",
              "symptoms":["none"],
              "checkedAt":"2026-10-31T07:15:00Z",
              "utcOffsetMinutes":0,
              "environment":"iss",
              "source":"synthetic",
              "isSynthetic":true,
              "createdAt":"2026-10-31T07:15:00Z"
            }
        """
        const val ALERT = """
            {
              "schemaVersion":"1.0",
              "id":"alert-1",
              "astronautId":"demo-astronaut-01",
              "createdAt":"2026-10-31T07:15:00Z",
              "level":"warning",
              "category":"vital",
              "ruleId":"HR-HIGH",
              "ruleRevision":1,
              "ruleSetVersion":"1.0.0",
              "title":"High heart rate",
              "explanation":"Heart rate is above the expected range.",
              "evidence":[{"field":"heart_rate.bpm","observed":120.0,"unit":"bpm","comparator":">","threshold":100.0,"observedAt":"2026-10-31T07:15:00Z"}],
              "recommendedAction":"Contact the medical team.",
              "disclaimer":"This is not medical advice.",
              "source":{"ref":"rules","title":"Rule catalog"},
              "triggeredBy":{"measurementIds":[],"symptomCheckinIds":[]},
              "isSynthetic":true
            }
        """
    }
}
