package com.revnix.android

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

class InstallReferrersTest {

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

    private fun posted(): Pair<String, String?> {
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/v1/installs", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        return body["installReferrer"]!!.jsonPrimitive.content to
            body["referrerSource"]?.jsonPrimitive?.content
    }

    @Test
    fun `meta beats an organic play referrer`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val storage = MemoryStorage()

        InstallReferrers.report(
            listOf(
                "meta" to "utm_source=fb4a&utm_campaign=spring",
                "play" to "utm_source=google-play&utm_medium=organic",
            ),
            makeClient(),
            storage,
            keyCollected,
            null,
            null,
        )

        assertEquals("utm_source=fb4a&utm_campaign=spring" to "meta", posted())
        assertEquals("1", storage.get(keyCollected))
    }

    @Test
    fun `blank and null candidates are skipped and preinstall is last`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val storage = MemoryStorage()

        InstallReferrers.report(
            listOf(
                "meta" to null,
                "huawei" to "   ",
                "preinstall" to "utm_source=oem",
            ),
            makeClient(),
            storage,
            keyCollected,
            null,
            null,
        )

        assertEquals("utm_source=oem" to "preinstall", posted())
    }

    @Test
    fun `all blank posts nothing and still latches`() {
        val storage = MemoryStorage()

        InstallReferrers.report(
            listOf("meta" to null, "huawei" to "", "preinstall" to null),
            makeClient(),
            storage,
            keyCollected,
            null,
            null,
        )

        assertEquals("1", storage.get(keyCollected))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `the latch is the one Play already writes, shared across sources`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val storage = MemoryStorage()
        val key = PlayInstallReferrer.collectedKey("cust_test")
        assertEquals(keyCollected, key)

        InstallReferrers.report(
            listOf("meta" to "utm_source=ig"),
            makeClient(),
            storage,
            key,
            null,
            null,
        )

        // Play reads the same key, so a Meta answer stops every later launch
        // from binding the Play service at all.
        assertEquals("1", storage.get(PlayInstallReferrer.collectedKey("cust_test")))
    }
}
