package com.mivimoose.core

import com.mivimoose.core.vision.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test

/** One physical card is one scan, however long it's held and however it leaves. */
class TrackerTest {
    private class Run(fps: Double = 15.0) {
        val tracker = Tracker()
        val dt = 1 / fps
        var t = 0.0
        val confirmed = ArrayList<String>()

        fun frames(seconds: Double, sightings: Map<String, Float>, inView: Boolean) {
            repeat((seconds / dt).toInt()) {
                t += dt
                tracker.update(t, sightings, inView)?.let { confirmed += it }
            }
        }
    }

    private val a = mapOf("A" to 0.9f)
    private val b = mapOf("B" to 0.9f)
    private val none = emptyMap<String, Float>()

    @Test
    fun heldCardLeavingIsNotScannedAgain() {
        for (fps in listOf(7.0, 10.0, 15.0, 20.0)) for (hold in listOf(1.0, 2.0, 5.0, 20.0)) {
            val r = Run(fps)
            r.frames(hold, a, true)
            r.frames(5.0, none, false)
            assertEquals("fps $fps, held $hold s", listOf("A"), r.confirmed)
        }
    }

    @Test
    fun showingItAgainAfterItWasGoneIsASecondScan() {
        val r = Run()
        r.frames(2.0, a, true)
        r.frames(2.0, none, false)
        r.frames(1.0, a, true)
        assertEquals(listOf("A", "A"), r.confirmed)
    }

    @Test
    fun nextCardInAStackScansAtOnce() {
        val r = Run(10.0)
        r.frames(3.0, a, true)          // plenty of evidence for A
        val before = r.t
        var at = -1.0
        repeat(20) { r.t += r.dt; r.tracker.update(r.t, b, true)?.let { c -> r.confirmed += c; if (at < 0) at = r.t } }
        assertEquals(listOf("A", "B"), r.confirmed)
        assert(at - before <= 0.4) { "B took ${at - before} s" }
    }

    @Test
    fun blurredFrameWhileHeldIsNotAnotherCard() {
        val r = Run(10.0)
        r.frames(2.0, a, true)
        // two frames looking like B, in between frames of A
        repeat(3) { r.frames(0.1, a, true); r.frames(0.2, b, true) }
        assertEquals(listOf("A"), r.confirmed)
    }

    @Test
    fun glareWhileHeldIsNotASecondScan() {
        val r = Run()
        r.frames(2.0, a, true)
        r.frames(3.0, none, true)       // outline still there, card not recognised
        r.frames(2.0, a, true)
        assertEquals(listOf("A"), r.confirmed)
    }

    @Test
    fun aFewLostFramesAreNotASecondScan() {
        val r = Run()
        r.frames(2.0, a, true)
        r.frames(0.5, none, false)      // autofocus hunting: nothing found at all for a moment
        r.frames(2.0, a, true)
        assertEquals(listOf("A"), r.confirmed)
    }

    @Test
    fun swappingForAnotherCardScansIt() {
        val r = Run()
        r.frames(2.0, a, true)
        r.frames(0.3, none, false)
        r.frames(2.0, b, true)
        assertEquals(listOf("A", "B"), r.confirmed)
    }

    @Test
    fun unlockDoesNotConfirmFromOldEvidence() {
        val r = Run()
        r.frames(2.0, a, true)
        r.tracker.unlock()
        r.t += r.dt
        assertEquals(null, r.tracker.update(r.t, none, false))
    }
}
