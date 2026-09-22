package com.revnix.android

import com.android.installreferrer.api.InstallReferrerClient
import com.revnix.MemoryStorage
import com.revnix.RevnixClient
import com.revnix.RevnixConfig
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class PlayInstallReferrerTest {

    private val keyCollected = "revnix.installReferrerCollected.cust_test"

    private lateinit var server: MockWebServer
    private val clients = mutableListOf<RevnixClient>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
        server.shutdown()
    }

    private fun makeClient(): RevnixClient = RevnixClient(
        RevnixConfig(
            apiKey = "rvx_pk_test_abc",
            baseUrl = server.url("/").toString(),
            storage = MemoryStorage(),
        )
    ).also { clients += it }

    @Test
    fun `OK with a real referrer posts it and latches`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = makeClient()
        val storage = MemoryStorage()

        PlayInstallReferrer.report(
            InstallReferrerClient.InstallReferrerResponse.OK,
            { "utm_source=instagram&utm_medium=cpc" },
            client,
            storage,
            keyCollected,
            null,
            null,
        )

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/v1/installs", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            "utm_source=instagram&utm_medium=cpc",
            body["installReferrer"]!!.jsonPrimitive.content,
        )
        assertEquals("1", storage.get(keyCollected))
    }

    @Test
    fun `OK with a blank referrer makes no request but still latches`() {
        val client = makeClient()
        val storage = MemoryStorage()

        PlayInstallReferrer.report(
            InstallReferrerClient.InstallReferrerResponse.OK,
            { "   " },
            client,
            storage,
            keyCollected,
            null,
            null,
        )

        // A blank referrer IS an answer — an organic install.
        assertEquals("1", storage.get(keyCollected))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `OK with a throwing read makes no request and does not latch`() {
        val client = makeClient()
        val storage = MemoryStorage()

        PlayInstallReferrer.report(
            InstallReferrerClient.InstallReferrerResponse.OK,
            { throw IllegalStateException("dead binder") },
            client,
            storage,
            keyCollected,
            null,
            null,
        )

        // A dead binder is transient — the next cold start must ask again.
        assertNull(storage.get(keyCollected))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `FEATURE_NOT_SUPPORTED makes no request but latches`() {
        val client = makeClient()
        val storage = MemoryStorage()

        PlayInstallReferrer.report(
            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
            { null },
            client,
            storage,
            keyCollected,
            null,
            null,
        )

        assertEquals("1", storage.get(keyCollected))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `SERVICE_UNAVAILABLE makes no request and does not latch`() {
        val client = makeClient()
        val storage = MemoryStorage()

        PlayInstallReferrer.report(
            InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE,
            { null },
            client,
            storage,
            keyCollected,
            null,
            null,
        )

        assertNull(storage.get(keyCollected))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }
}
