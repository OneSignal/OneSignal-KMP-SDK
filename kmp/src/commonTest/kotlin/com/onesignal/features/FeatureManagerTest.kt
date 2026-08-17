package com.onesignal.features

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureFlagTest {
    @Test
    fun remoteKeysAreLowercaseWithUnderscores() {
        val keyPattern = Regex("^[a-z0-9_]+$")
        for (feature in FeatureFlag.entries) {
            assertTrue(keyPattern.matches(feature.key), feature.key)
        }
    }

    @Test
    fun identityVerificationIsImmediate() {
        assertEquals("sdk_identity_verification", FeatureFlag.SDK_IDENTITY_VERIFICATION.key)
        assertEquals(
            FeatureActivationMode.IMMEDIATE,
            FeatureFlag.SDK_IDENTITY_VERIFICATION.activationMode,
        )
    }

    @Test
    fun customLoggingIsAppStartup() {
        assertEquals("sdk_custom_logging", FeatureFlag.SDK_CUSTOM_LOGGING.key)
        assertEquals(
            FeatureActivationMode.APP_STARTUP,
            FeatureFlag.SDK_CUSTOM_LOGGING.activationMode,
        )
    }
}

class FeatureManagerTest {
    @Test
    fun isEnabledIsFalseWhenKeyIsAbsent() {
        val manager = FeatureManager()
        manager.refresh(emptyList(), applyAppStartupFlags = true)
        assertFalse(manager.isEnabled(FeatureFlag.SDK_IDENTITY_VERIFICATION))
        assertEquals(emptyList(), manager.enabledFeatureKeys())
    }

    @Test
    fun processStartEnablesPresentKeys() {
        val manager = FeatureManager()
        manager.refresh(
            listOf(FeatureFlag.SDK_IDENTITY_VERIFICATION.key),
            applyAppStartupFlags = true,
        )
        assertTrue(manager.isEnabled(FeatureFlag.SDK_IDENTITY_VERIFICATION))
        assertEquals(
            listOf(FeatureFlag.SDK_IDENTITY_VERIFICATION.key),
            manager.enabledFeatureKeys(),
        )
    }

    @Test
    fun processStartCanonicalizesRemoteKeyCasing() {
        val manager = FeatureManager()
        manager.refresh(listOf("SDK_Identity_Verification"), applyAppStartupFlags = true)
        assertTrue(manager.isEnabled(FeatureFlag.SDK_IDENTITY_VERIFICATION))
    }

    @Test
    fun immediateFlagFlipsMidSession() {
        val manager = FeatureManager()
        manager.refresh(emptyList(), applyAppStartupFlags = true)
        assertFalse(manager.isEnabled(FeatureFlag.SDK_IDENTITY_VERIFICATION))

        manager.refresh(
            listOf(FeatureFlag.SDK_IDENTITY_VERIFICATION.key),
            applyAppStartupFlags = false,
        )
        assertTrue(manager.isEnabled(FeatureFlag.SDK_IDENTITY_VERIFICATION))
    }

    @Test
    fun appStartupFlagDoesNotFlipMidSession() {
        val manager = FeatureManager()
        manager.refresh(
            listOf(FeatureFlag.SDK_CUSTOM_LOGGING.key),
            applyAppStartupFlags = true,
        )
        assertTrue(manager.isEnabled(FeatureFlag.SDK_CUSTOM_LOGGING))

        val deferred = manager.refresh(emptyList(), applyAppStartupFlags = false)
        assertTrue(manager.isEnabled(FeatureFlag.SDK_CUSTOM_LOGGING))
        assertEquals(1, deferred.size)
        assertEquals(FeatureFlag.SDK_CUSTOM_LOGGING.key, deferred[0].key)
        assertFalse(deferred[0].desiredEnabled)
        assertTrue(deferred[0].latchedEnabled)
    }

    @Test
    fun processStartRefreshDoesNotReportDeferred() {
        val manager = FeatureManager()
        val deferred =
            manager.refresh(
                listOf(FeatureFlag.SDK_CUSTOM_LOGGING.key),
                applyAppStartupFlags = true,
            )
        assertEquals(emptyList(), deferred)
    }

    @Test
    fun immediateFlagFlipIsNotDeferred() {
        val manager = FeatureManager()
        manager.refresh(emptyList(), applyAppStartupFlags = true)
        val deferred =
            manager.refresh(
                listOf(FeatureFlag.SDK_IDENTITY_VERIFICATION.key),
                applyAppStartupFlags = false,
            )
        assertEquals(emptyList(), deferred)
        assertTrue(manager.isEnabled(FeatureFlag.SDK_IDENTITY_VERIFICATION))
    }

    @Test
    fun appStartupFlagAppliesOnNextProcessStart() {
        val first = FeatureManager()
        first.refresh(
            listOf(FeatureFlag.SDK_CUSTOM_LOGGING.key),
            applyAppStartupFlags = true,
        )
        first.refresh(emptyList(), applyAppStartupFlags = false)
        assertTrue(first.isEnabled(FeatureFlag.SDK_CUSTOM_LOGGING))

        val nextProcess = FeatureManager()
        nextProcess.refresh(emptyList(), applyAppStartupFlags = true)
        assertFalse(nextProcess.isEnabled(FeatureFlag.SDK_CUSTOM_LOGGING))
    }

    @Test
    fun unlatchedAppStartupFlagAppliesOnFirstRefreshEvenMidSession() {
        val manager = FeatureManager()
        manager.refresh(
            listOf(FeatureFlag.SDK_CUSTOM_LOGGING.key),
            applyAppStartupFlags = false,
        )
        assertTrue(manager.isEnabled(FeatureFlag.SDK_CUSTOM_LOGGING))
    }

    @Test
    fun localOverridesCountAsEnabled() {
        val manager = FeatureManager()
        manager.refresh(
            emptyList(),
            applyAppStartupFlags = true,
            localOverrides = listOf(FeatureFlag.SDK_IDENTITY_VERIFICATION.key),
        )
        assertTrue(manager.isEnabled(FeatureFlag.SDK_IDENTITY_VERIFICATION))
    }
}
