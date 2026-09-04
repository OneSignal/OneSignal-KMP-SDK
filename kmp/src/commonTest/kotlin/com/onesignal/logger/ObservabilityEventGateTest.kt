package com.onesignal.logger

import com.onesignal.features.FeatureFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservabilityEventGateTest {
    private val flag = FeatureFlag.SDK_EVENT_DEVICE_GESTURE

    @Test
    fun requiresFlagIsOffUntilTheFlagIsOn() {
        val gate = ObservabilityEventGate.RequiresFlag(flag)

        assertFalse(gate.allows { false })
        assertTrue(gate.allows { true })
        assertEquals("sdk_event_device_gesture is off", gate.blockedBy())
    }

    @Test
    fun unlessFlagIsOnUntilTheFlagIsOn() {
        // The kill-switch shape: a fact wanted from the first launch, before any flags fetch.
        val gate = ObservabilityEventGate.UnlessFlag(flag)

        assertTrue(gate.allows { false })
        assertFalse(gate.allows { true })
        assertEquals("sdk_event_device_gesture is on", gate.blockedBy())
    }

    @Test
    fun alwaysNeverConsultsTheFlags() {
        var asked = false

        assertTrue(ObservabilityEventGate.Always.allows { asked = true; false })

        assertFalse(asked)
    }

    @Test
    fun aGateAsksOnlyForItsOwnFlag() {
        val asked = mutableListOf<FeatureFlag>()

        ObservabilityEventGate.RequiresFlag(flag).allows { asked.add(it) }
        ObservabilityEventGate.UnlessFlag(FeatureFlag.SDK_IDENTITY_VERIFICATION).allows { asked.add(it) }

        assertEquals(listOf(flag, FeatureFlag.SDK_IDENTITY_VERIFICATION), asked)
    }
}
