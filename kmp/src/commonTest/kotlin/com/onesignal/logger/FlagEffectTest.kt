package com.onesignal.logger

import com.onesignal.features.FeatureFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlagEffectTest {
    private val flag = FeatureFlag.SDK_EVENT_DEVICE_GESTURE

    @Test
    fun enablesIsOffUntilTheFlagIsOn() {
        assertFalse(FlagEffect.ENABLES.allows(flag) { false })
        assertTrue(FlagEffect.ENABLES.allows(flag) { true })
        assertEquals("sdk_event_device_gesture is off", FlagEffect.ENABLES.blockedBy(flag))
    }

    @Test
    fun disablesIsOnUntilTheFlagIsOn() {
        // The kill-switch shape: a fact wanted from the first launch, before any flags fetch.
        assertTrue(FlagEffect.DISABLES.allows(flag) { false })
        assertFalse(FlagEffect.DISABLES.allows(flag) { true })
        assertEquals("sdk_event_device_gesture is on", FlagEffect.DISABLES.blockedBy(flag))
    }

    @Test
    fun noFlagMeansAlwaysAndNeverConsultsTheReader() {
        var asked = false

        assertTrue(FlagEffect.ENABLES.allows(null) { asked = true; false })
        assertTrue(FlagEffect.DISABLES.allows(null) { asked = true; false })

        assertFalse(asked)
        assertEquals("nothing", FlagEffect.ENABLES.blockedBy(null))
    }

    @Test
    fun onlyTheGivenFlagIsAskedAbout() {
        val asked = mutableListOf<FeatureFlag>()

        FlagEffect.ENABLES.allows(flag) { asked.add(it) }
        FlagEffect.DISABLES.allows(FeatureFlag.SDK_IDENTITY_VERIFICATION) { asked.add(it) }

        assertEquals(listOf(flag, FeatureFlag.SDK_IDENTITY_VERIFICATION), asked)
    }
}
