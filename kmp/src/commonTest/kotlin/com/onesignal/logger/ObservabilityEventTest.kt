package com.onesignal.logger

import com.onesignal.features.FeatureActivationMode
import com.onesignal.features.FeatureFlag
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun eachFlaggedEventOwnsAnImmediateFlagNamedAfterIt() {
        // A flag that turns an event on is sdk_event_<name>; a kill switch is sdk_event_<name>_disabled.
        // IMMEDIATE either way, so a rollout or a stop does not wait for a cold start.
        for (event in ObservabilityEvent.entries) {
            val flag = event.flag ?: continue
            val name = event.eventName.removePrefix("sdk.")
            val expectedKey =
                when (event.flagEffect) {
                    FlagEffect.ENABLES -> "sdk_event_$name"
                    FlagEffect.DISABLES -> "sdk_event_${name}_disabled"
                }
            assertEquals(expectedKey, flag.key, event.name)
            assertEquals(FeatureActivationMode.IMMEDIATE, flag.activationMode, event.name)
        }
        val flags = ObservabilityEvent.entries.mapNotNull { it.flag }
        assertEquals(flags.size, flags.toSet().size)
    }

    @Test
    fun deviceGestureIsOffUntilItsCatalogFlagIsOn() {
        assertEquals("sdk.device_gesture", ObservabilityEvent.DEVICE_GESTURE.eventName)
        assertEquals(FeatureFlag.SDK_EVENT_DEVICE_GESTURE, ObservabilityEvent.DEVICE_GESTURE.flag)
        assertEquals(FlagEffect.ENABLES, ObservabilityEvent.DEVICE_GESTURE.flagEffect)
    }
}
