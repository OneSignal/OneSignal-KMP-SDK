package com.onesignal.features

/**
 * Controls when a remote change for a [FeatureFlag] is applied to the current process.
 */
enum class FeatureActivationMode {
    /** Apply the remote value immediately during this app run. */
    IMMEDIATE,

    /**
     * Latch at process start (or the first time the flag is seen). Later remote
     * updates wait until the next process.
     */
    APP_STARTUP,
}

/**
 * Backend-driven feature switches shared by Android and iOS hosts.
 *
 * [key] values are lowercase Turbine `features` array entries. Unknown remote keys
 * are ignored; missing catalog entries stay disabled.
 */
enum class FeatureFlag(
    val key: String,
    val activationMode: FeatureActivationMode,
) {
    /** JWT signing of SDK requests. IMMEDIATE so a kill-switch does not need a cold start. */
    SDK_IDENTITY_VERIFICATION(
        "sdk_identity_verification",
        FeatureActivationMode.IMMEDIATE,
    ),

    /**
     * Routes observability through the shared `logger` module instead of legacy OTel.
     * APP_STARTUP because switching pipelines mid-session is unsafe.
     */
    SDK_CUSTOM_LOGGING(
        "sdk_custom_logging",
        FeatureActivationMode.APP_STARTUP,
    ),

    /**
     * Ships the `sdk.device_gesture` event ([com.onesignal.logger.ObservabilityEvent.DEVICE_GESTURE]).
     * IMMEDIATE so turning the event on reaches installs on their next flags fetch, and turning it
     * off stops them the same way, without a cold start in either direction.
     */
    SDK_EVENT_DEVICE_GESTURE(
        "sdk_event_device_gesture",
        FeatureActivationMode.IMMEDIATE,
    ),

    /**
     * Kill switch for the device gesture that copies the push subscription ID to the clipboard:
     * present means off, so the gesture works before the first flags fetch and a customer can
     * be opted out per app. IMMEDIATE so opting out lands on the next fetch, not the next cold
     * start.
     */
    SDK_DEVICE_GESTURE_DISABLED(
        "sdk_device_gesture_disabled",
        FeatureActivationMode.IMMEDIATE,
    ),
    ;

    fun isEnabledIn(enabledKeys: Set<String>): Boolean = enabledKeys.contains(key)
}

/** Lowercase so remote keys match the catalog regardless of wire casing. */
internal fun canonicalizeFeatureFlagId(raw: String): String =
    buildString(raw.length) {
        for (c in raw) {
            append(c.lowercaseChar())
        }
    }
