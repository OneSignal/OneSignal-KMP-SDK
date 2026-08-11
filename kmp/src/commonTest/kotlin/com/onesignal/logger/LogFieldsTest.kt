package com.onesignal.logger

import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFieldsTest {
    @Test
    fun topLevelIncludesExpectedKeysAndOmitsNullWrapper() = runTest {
        val provider = FakePlatformProvider()
        val attrs = LogFieldsTopLevel(provider).getAttributes()

        assertEquals("install-abc", attrs["ossdk.install_id"])
        assertEquals("android", attrs["ossdk.sdk_base"])
        assertEquals("Android", attrs["os.name"])
        assertFalse(attrs.containsKey("ossdk.sdk_wrapper"))
        // Build-stamped KMP provenance always rides along in the resource header.
        // Value is git-describe derived (non-deterministic), so only assert presence.
        assertTrue(attrs["ossdk.kmp_version"]?.isNotEmpty() == true)
        // Language versions are opt-in; absent when the host does not supply them.
        assertFalse(attrs.containsKey("ossdk.kotlin_version"))
        assertFalse(attrs.containsKey("ossdk.swift_version"))
    }

    @Test
    fun topLevelIncludesKotlinAndSwiftVersionsWhenProvided() = runTest {
        val provider =
            FakePlatformProvider(
                kotlinVersion = "2.0.21",
                swiftVersion = "5.10",
            )
        val attrs = LogFieldsTopLevel(provider).getAttributes()

        assertEquals("2.0.21", attrs["ossdk.kotlin_version"])
        assertEquals("5.10", attrs["ossdk.swift_version"])
    }

    @Test
    fun topLevelOmitsBlankLanguageVersions() = runTest {
        val provider =
            FakePlatformProvider(
                kotlinVersion = "   ",
                swiftVersion = "",
            )
        val attrs = LogFieldsTopLevel(provider).getAttributes()

        assertFalse(attrs.containsKey("ossdk.kotlin_version"))
        assertFalse(attrs.containsKey("ossdk.swift_version"))
    }

    @Test
    fun topLevelMergesAdditionalVersionAttributesUnderOssdkPrefix() = runTest {
        val provider =
            FakePlatformProvider(
                kotlinVersion = "2.1.0",
                swiftVersion = "6.0",
                additionalVersionAttributes =
                mapOf(
                    "java_version" to "17",
                    "xcode_version" to "16.2",
                    // Accidental ossdk. prefix is stripped (no double prefix).
                    "ossdk.ndk_version" to "26.1",
                    // Blank extras are omitted.
                    "agp_version" to "  ",
                ),
            )
        val attrs = LogFieldsTopLevel(provider).getAttributes()

        assertEquals("17", attrs["ossdk.java_version"])
        assertEquals("16.2", attrs["ossdk.xcode_version"])
        assertEquals("26.1", attrs["ossdk.ndk_version"])
        assertEquals("2.1.0", attrs["ossdk.kotlin_version"])
        assertEquals("6.0", attrs["ossdk.swift_version"])
        assertFalse(attrs.containsKey("ossdk.ossdk.ndk_version"))
        assertFalse(attrs.containsKey("ossdk.agp_version"))
    }

    @Test
    fun topLevelRejectsReservedAdditionalVersionAttributeKeys() = runTest {
        // Dedicated language versions intentionally null — reserved extras must
        // still be dropped, not fill the canonical keys.
        val provider =
            FakePlatformProvider(
                additionalVersionAttributes =
                mapOf(
                    "kotlin_version" to "from-extras",
                    "swift_version" to "from-extras",
                    "install_id" to "forged-install",
                    "kmp_version" to "forged-kmp",
                    "sdk_wrapper" to "forged-wrapper",
                    // Leading whitespace must not keep removePrefix from matching,
                    // or the reserved key slips through as ossdk.ossdk.kotlin_version.
                    " ossdk.kotlin_version" to "spaced-reserved",
                    "java_version" to "17",
                ),
            )
        val attrs = LogFieldsTopLevel(provider).getAttributes()

        assertFalse(attrs.containsKey("ossdk.kotlin_version"))
        assertFalse(attrs.containsKey("ossdk.swift_version"))
        assertFalse(attrs.containsKey("ossdk.sdk_wrapper"))
        assertFalse(attrs.containsKey("ossdk.ossdk.kotlin_version"))
        assertEquals("install-abc", attrs["ossdk.install_id"])
        assertTrue(attrs["ossdk.kmp_version"]?.isNotEmpty() == true)
        assertTrue(attrs["ossdk.kmp_version"] != "forged-kmp")
        assertEquals("17", attrs["ossdk.java_version"])
    }

    @Test
    fun perEventIncludesDynamicValuesAndUniqueRecordId() {
        val provider = FakePlatformProvider()
        val fields = LogFieldsPerEvent(provider)

        val a = fields.getAttributes()
        val b = fields.getAttributes()

        assertEquals("app-123", a["ossdk.app_id"])
        assertEquals("foreground", a["app.state"])
        assertEquals("1234", a["process.uptime"])
        assertEquals("test-thread", a["thread.name"])
        // record uid must be unique per event
        assertTrue(a["log.record.uid"] != b["log.record.uid"])
    }

    @Test
    fun featureFlagsAreSortedCsvAndOmittedWhenEmpty() {
        val provider = FakePlatformProvider(enabledFeatureFlags = listOf("zeta", "alpha"))
        val attrs = LogFieldsPerEvent(provider).getAttributes()
        assertEquals("alpha,zeta", attrs["ossdk.feature_flags"])

        val empty = LogFieldsPerEvent(FakePlatformProvider()).getAttributes()
        assertFalse(empty.containsKey("ossdk.feature_flags"))
    }
}
