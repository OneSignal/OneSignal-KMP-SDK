package com.onesignal.logger

import com.onesignal.features.FeatureFlag

/**
 * The closed set of named events the SDK can ship on the log pipeline.
 *
 * An event is an OTLP log record carrying an `event.name` attribute. It goes through the same
 * remote sink as `Logging.*` lines and crash records but, like crashes, is not filtered by
 * severity. Each entry binds its name to the catalog [FeatureFlag] that turns it on, so an
 * event without a catalog entry, or a renamed flag, fails the build instead of silently never
 * sending. Both gates must pass: the pipeline gate (`log_level` present and not `NONE`) and
 * the event's own flag.
 *
 * Names are dot-namespaced under `sdk.` and describe the source, not the action behind it,
 * which can change; attributes say what happened. Skipping the severity filter makes every
 * entry a volume decision: ship it default off, turn it on in ConfigCat narrow first, and
 * state the expected records per install per day in the PR that adds it.
 */
enum class SdkEvent(
    /** Value of the `event.name` attribute and of the record body. */
    val eventName: String,
    /** Catalog flag that must be enabled for the event to ship. */
    val flag: FeatureFlag,
) {
    /**
     * The device gesture was recognised. Fired at the detector, not deduped, with
     * `gesture.result`, `gesture.id_kind` and `gesture.id`. Temporary: it answers whether the
     * gesture gets used and comes out once that is known.
     */
    DEVICE_GESTURE("sdk.device_gesture", FeatureFlag.SDK_EVENT_DEVICE_GESTURE),
}
