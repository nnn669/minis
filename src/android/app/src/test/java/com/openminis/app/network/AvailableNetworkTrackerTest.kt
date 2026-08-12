package com.openminis.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailableNetworkTrackerTest {
    private val tracker = AvailableNetworkTracker<String>()

    @Test
    fun `first route connects and last lost route disconnects`() {
        val connected = tracker.update("wifi", available = true)
        assertTrue(connected.membershipChanged)
        assertTrue(connected.isConnected)

        val disconnected = tracker.update("wifi", available = false)
        assertTrue(disconnected.membershipChanged)
        assertFalse(disconnected.isConnected)
    }

    @Test
    fun `losing one of two routes remains connected`() {
        tracker.update("wifi", available = true)
        tracker.update("cellular", available = true)

        val result = tracker.update("wifi", available = false)

        assertTrue(result.membershipChanged)
        assertTrue(result.isConnected)
    }

    @Test
    fun `duplicate callbacks do not report a route change`() {
        tracker.update("wifi", available = true)

        val duplicateAvailable = tracker.update("wifi", available = true)
        val unknownLost = tracker.update("cellular", available = false)

        assertFalse(duplicateAvailable.membershipChanged)
        assertTrue(duplicateAvailable.isConnected)
        assertFalse(unknownLost.membershipChanged)
        assertTrue(unknownLost.isConnected)
    }

    @Test
    fun `clear resets connectivity`() {
        tracker.update("wifi", available = true)
        tracker.clear()

        val result = tracker.update("wifi", available = false)

        assertFalse(result.membershipChanged)
        assertFalse(result.isConnected)
    }
}
