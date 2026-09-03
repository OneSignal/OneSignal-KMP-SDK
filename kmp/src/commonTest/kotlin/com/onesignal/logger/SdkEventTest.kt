package com.onesignal.logger

import com.onesignal.features.FeatureActivationMode
import com.onesignal.features.FeatureFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The naming rules for events, enforced so a new entry cannot drift from them. */
class SdkEventTest {
    @Test
    fun eventNamesAreDotNamespacedUnderSdk() {
        val namePattern = Regex("^sdk\\.[a-z][a-z0-9_]*$")
        for (event in SdkEvent.entries) {
            assertTrue(namePattern.matches(event.eventName), event.eventName)
        }
    }

    @Test
    fun eventNamesAreUnique() {
        assertEquals(SdkEvent.entries.size, SdkEvent.entries.map { it.eventName }.toSet().size)
    }

    @Test
    fun eachEventOwnsAnImmediateFlagNamedAfterIt() {
        for (event in SdkEvent.entries) {
            val expectedKey = "sdk_event_" + event.eventName.removePrefix("sdk.") + "_enabled"
            assertEquals(expectedKey, event.flag.key, event.name)
            assertEquals(FeatureActivationMode.IMMEDIATE, event.flag.activationMode, event.name)
        }
        assertEquals(SdkEvent.entries.size, SdkEvent.entries.map { it.flag }.toSet().size)
    }

    @Test
    fun deviceGestureIsBoundToItsCatalogFlag() {
        assertEquals("sdk.device_gesture", SdkEvent.DEVICE_GESTURE.eventName)
        assertEquals(FeatureFlag.SDK_EVENT_DEVICE_GESTURE, SdkEvent.DEVICE_GESTURE.flag)
    }
}
