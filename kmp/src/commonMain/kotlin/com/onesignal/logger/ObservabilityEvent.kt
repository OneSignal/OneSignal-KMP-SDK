package com.onesignal.logger

import com.onesignal.features.FeatureFlag
import com.onesignal.features.IFeatureFlagReader

/**
 * The closed set of events the SDK ships about itself on the log pipeline: OTLP records carrying
 * `event.name`, through the same remote telemetry as log lines and crash records and, like crashes,
 * not filtered by severity. Each entry names the catalog flag that gates it and what that flag
 * means, so a missing or renamed flag fails the build instead of silently never sending. Unfiltered
 * means every entry is a volume decision: prefer a flag that is off by default and turn it on narrow
 * first.
 */
enum class ObservabilityEvent(
    /** The `event.name` attribute; also the record body. */
    val eventName: String,
    /** The catalog flag that gates the event, or null when only the pipeline gate applies. */
    internal val flag: FeatureFlag? = null,
    /** Whether [flag] turns the event on (the default) or off (a kill switch). */
    internal val flagEffect: FlagEffect = FlagEffect.ENABLES,
) {
    /**
     * Fired when the detector recognises the gesture, not deduped, with `gesture.result` and, when
     * something was copied, `gesture.push_subscription_id`. Temporary: comes out once its usage
     * question is answered.
     */
    DEVICE_GESTURE("sdk.device_gesture", FeatureFlag.SDK_EVENT_DEVICE_GESTURE),
    ;

    internal fun allows(flags: IFeatureFlagReader): Boolean = flagEffect.allows(flag, flags)

    internal fun blockedBy(): String = flagEffect.blockedBy(flag)
}
