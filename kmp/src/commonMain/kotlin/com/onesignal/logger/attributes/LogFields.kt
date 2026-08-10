package com.onesignal.logger.attributes

import com.onesignal.logger.ILoggerPlatformProvider
import com.onesignal.logger.LoggerBuildInfo
import com.onesignal.logger.internal.randomUuidString

internal fun <K, V> MutableMap<K, V>.putIfValueNotNull(key: K, value: V?): MutableMap<K, V> {
    if (value != null) {
        this[key] = value
    }
    return this
}

/** Like [putIfValueNotNull], but also skips blank strings so filter attrs stay sparse. */
internal fun MutableMap<String, String>.putIfValueNotBlank(
    key: String,
    value: String?,
): MutableMap<String, String> {
    if (!value.isNullOrBlank()) {
        this[key] = value
    }
    return this
}

/**
 * Hosts may pass bare suffixes (`java_version`) or accidentally include the
 * `ossdk.` prefix; normalize to the bare suffix so we never emit
 * `ossdk.ossdk.*`.
 */
internal fun normalizeOssdkAttributeSuffix(key: String): String =
    key.removePrefix("ossdk.").trim()

/**
 * Canonical top-level `ossdk.*` suffixes owned by dedicated provider fields /
 * core resource attrs. Entries in [ILoggerPlatformProvider.additionalVersionAttributes]
 * that normalize to one of these are dropped entirely — even when the dedicated
 * value is null — so extras cannot populate reserved labels.
 */
internal val RESERVED_TOP_LEVEL_OSSDK_SUFFIXES: Set<String> =
    setOf(
        "install_id",
        "sdk_base",
        "sdk_base_version",
        "kmp_version",
        "app_package_id",
        "app_version",
        "sdk_wrapper",
        "sdk_wrapper_version",
        "kotlin_version",
        "swift_version",
    )

/**
 * Top-level / resource attributes. Included on every export and, per OTLP, attached
 * to the `resource` rather than each record. Only values that cannot change during
 * runtime belong here (they are fetched once and cached by the telemetry).
 *
 * Mirrors `OtelFieldsTopLevel` key-for-key, plus `ossdk.kmp_version`: the build
 * provenance of the shared KMP module (which hosts the logger today and more
 * features later). Because it is republished under the host SDK version, that
 * attribute is the only thing on the wire that ties a record back to the exact
 * KMP source that produced it.
 *
 * Optional host language / toolchain versions (`ossdk.kotlin_version`,
 * `ossdk.swift_version`, plus any `additionalVersionAttributes`) ride along so
 * dashboards can filter by the app's language stack.
 */
internal class LogFieldsTopLevel(
    private val platformProvider: ILoggerPlatformProvider,
) {
    suspend fun getAttributes(): Map<String, String> {
        val attributes: MutableMap<String, String> =
            mutableMapOf(
                "ossdk.install_id" to platformProvider.getInstallId(),
                "ossdk.sdk_base" to platformProvider.sdkBase,
                "ossdk.sdk_base_version" to platformProvider.sdkBaseVersion,
                "ossdk.kmp_version" to LoggerBuildInfo.KMP_VERSION,
                "ossdk.app_package_id" to platformProvider.appPackageId,
                "ossdk.app_version" to platformProvider.appVersion,
                "device.manufacturer" to platformProvider.deviceManufacturer,
                "device.model.identifier" to platformProvider.deviceModel,
                "os.name" to platformProvider.osName,
                "os.version" to platformProvider.osVersion,
                "os.build_id" to platformProvider.osBuildId,
            )

        attributes
            .putIfValueNotNull("ossdk.sdk_wrapper", platformProvider.sdkWrapper)
            .putIfValueNotNull("ossdk.sdk_wrapper_version", platformProvider.sdkWrapperVersion)
            .putIfValueNotBlank("ossdk.kotlin_version", platformProvider.kotlinVersion)
            .putIfValueNotBlank("ossdk.swift_version", platformProvider.swiftVersion)

        for ((key, value) in platformProvider.additionalVersionAttributes) {
            val suffix = normalizeOssdkAttributeSuffix(key)
            if (suffix.isEmpty() ||
                suffix in RESERVED_TOP_LEVEL_OSSDK_SUFFIXES ||
                value.isNullOrBlank()
            ) {
                continue
            }
            attributes["ossdk.$suffix"] = value
        }

        return attributes.toMap()
    }
}

/**
 * Per-event attributes. Recomputed for every record so each one reflects the current
 * state (IDs, app state, enabled feature flags, etc.).
 *
 * Mirrors `OtelFieldsPerEvent` key-for-key.
 */
internal class LogFieldsPerEvent(
    private val platformProvider: ILoggerPlatformProvider,
) {
    fun getAttributes(): Map<String, String> {
        val attributes: MutableMap<String, String> = mutableMapOf()

        attributes["log.record.uid"] = randomUuidString()

        attributes
            .putIfValueNotNull("ossdk.app_id", platformProvider.appId)
            .putIfValueNotNull("ossdk.onesignal_id", platformProvider.onesignalId)
            .putIfValueNotNull("ossdk.push_subscription_id", platformProvider.pushSubscriptionId)

        attributes["app.state"] = platformProvider.appState
        attributes["process.uptime"] = platformProvider.processUptime.toString()
        attributes["thread.name"] = platformProvider.currentThreadName

        val enabledFlags = platformProvider.enabledFeatureFlags
        if (enabledFlags.isNotEmpty()) {
            attributes["ossdk.feature_flags"] = enabledFlags.sorted().joinToString(",")
        }

        return attributes.toMap()
    }
}
