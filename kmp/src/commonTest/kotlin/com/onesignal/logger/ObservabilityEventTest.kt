package com.onesignal.logger

import com.onesignal.features.FeatureActivationMode
import com.onesignal.features.FeatureFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The naming rules for events and their flags, enforced so a new entry cannot drift from them. */
class ObservabilityEventTest {
    @Test
    fun eventNamesAreDotNamespacedUnderSdk() {
        val namePattern = Regex("^sdk\\.[a-z][a-z0-9_]*$")
        for (event in ObservabilityEvent.entries) {
            assertTrue(namePattern.matches(event.eventName), event.eventName)
        }
    }

    @Test
    fun eventNamesAreUnique() {
        assertEquals(ObservabilityEvent.entries.size, ObservabilityEvent.entries.map { it.eventName }.toSet().size)
    }

    @Test
    fun eachGatedEventOwnsAnImmediateFlagNamedAfterIt() {
        // A flag that turns an event on is sdk_event_<name>; a kill switch is sdk_event_<name>_disabled.
        // IMMEDIATE either way, so a rollout or a stop does not wait for a cold start.
        for (event in ObservabilityEvent.entries) {
            val name = event.eventName.removePrefix("sdk.")
            when (val gate = event.gate) {
                is ObservabilityEventGate.RequiresFlag -> assertEquals("sdk_event_$name", gate.flag.key, event.name)
                is ObservabilityEventGate.UnlessFlag -> assertEquals("sdk_event_${name}_disabled", gate.flag.key, event.name)
                ObservabilityEventGate.Always -> Unit
            }
            event.gate.flag?.let { assertEquals(FeatureActivationMode.IMMEDIATE, it.activationMode, event.name) }
        }
        val flags = ObservabilityEvent.entries.mapNotNull { it.gate.flag }
        assertEquals(flags.size, flags.toSet().size)
    }

    @Test
    fun deviceGestureRequiresItsCatalogFlag() {
        assertEquals("sdk.device_gesture", ObservabilityEvent.DEVICE_GESTURE.eventName)
        val gate = assertIs<ObservabilityEventGate.RequiresFlag>(ObservabilityEvent.DEVICE_GESTURE.gate)
        assertEquals(FeatureFlag.SDK_EVENT_DEVICE_GESTURE, gate.flag)
    }
}
