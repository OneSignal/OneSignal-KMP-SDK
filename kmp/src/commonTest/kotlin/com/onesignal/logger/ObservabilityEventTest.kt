package com.onesignal.logger

import com.onesignal.features.FeatureActivationMode
import com.onesignal.features.FeatureFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The naming rules for events, enforced so a new entry cannot drift from them. */
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
    fun eachEventOwnsAnImmediateFlagNamedAfterIt() {
        for (event in ObservabilityEvent.entries) {
            val expectedKey = "sdk_event_" + event.eventName.removePrefix("sdk.") + "_enabled"
            assertEquals(expectedKey, event.flag.key, event.name)
            assertEquals(FeatureActivationMode.IMMEDIATE, event.flag.activationMode, event.name)
        }
        assertEquals(ObservabilityEvent.entries.size, ObservabilityEvent.entries.map { it.flag }.toSet().size)
    }

    @Test
    fun deviceGestureIsBoundToItsCatalogFlag() {
        assertEquals("sdk.device_gesture", ObservabilityEvent.DEVICE_GESTURE.eventName)
        assertEquals(FeatureFlag.SDK_EVENT_DEVICE_GESTURE, ObservabilityEvent.DEVICE_GESTURE.flag)
    }
}
