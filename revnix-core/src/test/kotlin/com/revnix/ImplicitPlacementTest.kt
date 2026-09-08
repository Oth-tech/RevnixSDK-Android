package com.revnix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REV-272: the implicit placement contract, Android side.
 *
 * The server 400s a key it does not know, and the failure is SILENT from the
 * app's side — the moment simply never fires and nobody sees a paywall that was
 * configured. That makes the key spellings the one thing worth asserting hard,
 * alongside the loop guard's shape.
 */
class ImplicitPlacementTest {

    @Test
    fun `six keys match the server contract`() {
        assertEquals(
            listOf(
                "app_install",
                "app_launch",
                "session_start",
                "deeplink_open",
                "paywall_decline",
                "transaction_abandon",
            ),
            RevnixImplicitPlacement.entries.map { it.key },
        )
    }

    @Test
    fun `every key is one the catalog would accept`() {
        // A placement key must match ^[a-z0-9][a-z0-9._-]{0,63}$ server-side.
        // This is why the deep link moment is `deeplink_open` and not
        // Superwall's `deepLink_open` — the capital L could never be stored.
        val pattern = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        for (placement in RevnixImplicitPlacement.entries) {
            assertTrue(
                pattern.matches(placement.key),
                "${placement.key} would be refused by the catalog",
            )
        }
        assertFalse(pattern.matches("deepLink_open"))
    }

    @Test
    fun `keys round-trip`() {
        for (placement in RevnixImplicitPlacement.entries) {
            assertEquals(placement, RevnixImplicitPlacement.fromKey(placement.key))
        }
        // An ordinary placement must not be mistaken for one of the six — that
        // is what the loop guard keys off.
        assertNull(RevnixImplicitPlacement.fromKey("premium_button"))
        assertNull(RevnixImplicitPlacement.fromKey("app_launched"))
    }

    @Test
    fun `implicit placements are off until a handler is given`() {
        // The default has to be free: without a handler there is nothing to do
        // with a resolved paywall, and asking /v1/config for the whole fleet is
        // exactly the cost this feature is designed to avoid.
        val base = RevnixConfig(apiKey = "rvx_pk_test", baseUrl = "https://x.convex.site")
        assertFalse(base.implicitPlacementsEnabled)
        assertNull(base.lifecycle)
        assertEquals(30L * 60 * 1000, base.sessionTimeoutMs)

        assertTrue(base.copy(onImplicitPaywall = { }).implicitPlacementsEnabled)
        // The explicit switch wins over a handler, in both directions.
        assertFalse(
            base.copy(onImplicitPaywall = { }, implicitPlacements = false).implicitPlacementsEnabled,
        )
        assertTrue(base.copy(implicitPlacements = true).implicitPlacementsEnabled)
    }

    @Test
    fun `a lifecycle adapter reports both states and is cancellable`() {
        // Both transitions matter: a session is defined by time in the
        // BACKGROUND, which foreground events alone cannot measure.
        var handler: ((RevnixAppState) -> Unit)? = null
        val lifecycle = RevnixLifecycle { h ->
            handler = h
            { handler = null }
        }
        val seen = mutableListOf<RevnixAppState>()
        val cancel = lifecycle.onStateChange { seen.add(it) }
        handler?.invoke(RevnixAppState.BACKGROUND)
        handler?.invoke(RevnixAppState.FOREGROUND)
        assertEquals(listOf(RevnixAppState.BACKGROUND, RevnixAppState.FOREGROUND), seen)
        cancel()
        assertNull(handler)
    }

    @Test
    fun `the disabled adapter subscribes to nothing`() {
        var fired = 0
        val cancel = RevnixLifecycleDisabled.onStateChange { fired += 1 }
        cancel()
        assertEquals(0, fired)
    }
}
