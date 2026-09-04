package com.onesignal.logger

import com.onesignal.features.FeatureFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlagPolarityTest {
    private val flag = FeatureFlag.SDK_EVENT_DEVICE_GESTURE

    @Test
    fun enablesIsOffUntilTheFlagIsOn() {
        assertFalse(FlagPolarity.ENABLES.allows(flag) { false })
        assertTrue(FlagPolarity.ENABLES.allows(flag) { true })
        assertEquals("sdk_event_device_gesture is off", FlagPolarity.ENABLES.blockedBy(flag))
    }

    @Test
    fun disablesIsOnUntilTheFlagIsOn() {
        // The kill-switch shape: a fact wanted from the first launch, before any flags fetch.
        assertTrue(FlagPolarity.DISABLES.allows(flag) { false })
        assertFalse(FlagPolarity.DISABLES.allows(flag) { true })
        assertEquals("sdk_event_device_gesture is on", FlagPolarity.DISABLES.blockedBy(flag))
    }

    @Test
    fun noFlagMeansAlwaysAndNeverConsultsTheReader() {
        var asked = false

        assertTrue(FlagPolarity.ENABLES.allows(null) { asked = true; false })
        assertTrue(FlagPolarity.DISABLES.allows(null) { asked = true; false })

        assertFalse(asked)
        assertEquals("nothing", FlagPolarity.ENABLES.blockedBy(null))
    }

    @Test
    fun onlyTheGivenFlagIsAskedAbout() {
        val asked = mutableListOf<FeatureFlag>()

        FlagPolarity.ENABLES.allows(flag) { asked.add(it) }
        FlagPolarity.DISABLES.allows(FeatureFlag.SDK_IDENTITY_VERIFICATION) { asked.add(it) }

        assertEquals(listOf(flag, FeatureFlag.SDK_IDENTITY_VERIFICATION), asked)
    }
}
