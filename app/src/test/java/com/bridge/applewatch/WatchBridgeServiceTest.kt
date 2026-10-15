package com.bridge.applewatch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bridge.applewatch.service.WatchBridgeService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the Android-framework surface of WatchBridgeService:
 * the optional shared token (Q3), the peer gate, and a clean service lifecycle.
 *
 * The service's decision logic is covered separately and more thoroughly by
 * BridgePolicyTest; these tests only prove the framework wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchBridgeServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WatchBridgeService.clearAuthToken(context)
    }

    @After
    fun tearDown() {
        WatchBridgeService.clearAuthToken(context)
    }

    @Test
    fun `token defaults to disabled and persists once set`() {
        assertNull("an unconfigured bridge must not require a token", WatchBridgeService.authToken)

        WatchBridgeService.setAuthToken(context, "pixel-secret")
        assertEquals("pixel-secret", WatchBridgeService.authToken)

        // Prove it actually persisted to disk rather than only living in memory.
        WatchBridgeService.authTokenForTestReset()
        assertNull(WatchBridgeService.authToken)
        WatchBridgeService.loadAuthToken(context)
        assertEquals("pixel-secret", WatchBridgeService.authToken)
    }

    @Test
    fun `blank token clears the requirement`() {
        WatchBridgeService.setAuthToken(context, "pixel-secret")
        WatchBridgeService.setAuthToken(context, "   ")
        assertNull(WatchBridgeService.authToken)

        // A reload must also observe "no token" rather than an empty string.
        WatchBridgeService.authTokenForTestReset()
        WatchBridgeService.loadAuthToken(context)
        assertNull(WatchBridgeService.authToken)

        // clearAuthToken must remove the persisted value too, or a restart would
        // resurrect a token the user thought they had removed.
        WatchBridgeService.setAuthToken(context, "pixel-secret")
        WatchBridgeService.clearAuthToken(context)
        WatchBridgeService.loadAuthToken(context)
        assertNull(WatchBridgeService.authToken)
    }

    @Test
    fun `service starts foreground and shuts down cleanly`() {
        val controller = Robolectric.buildService(WatchBridgeService::class.java)
        val service = controller.create().get()

        assertNotNull("service instance should be created", service)
        assertFalse("service must not be marked shutting down while alive",
            WatchBridgeService.shuttingDownForTest)

        // onDestroy must complete without throwing and must flag the shutdown so the
        // accept loop suppresses its "HTTP Server Error" log.
        controller.destroy()
        assertTrue("onDestroy should record shutdown", WatchBridgeService.shuttingDownForTest)
    }

    @Test
    fun `peer gate is reachable through the service facade`() {
        assertTrue(WatchBridgeService.isPrivateNetworkAddress("192.168.1.20"))
        assertTrue(WatchBridgeService.isPrivateNetworkAddress("127.0.0.1"))
        assertFalse(WatchBridgeService.isPrivateNetworkAddress("8.8.8.8"))
        assertFalse(WatchBridgeService.isPrivateNetworkAddress(null))
    }
}
