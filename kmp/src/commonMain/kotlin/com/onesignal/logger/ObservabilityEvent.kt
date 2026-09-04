package com.onesignal.logger

import com.onesignal.features.FeatureFlag

/**
 * The closed set of events the SDK ships about itself on the log pipeline: OTLP records carrying
 * `event.name`, through the same remote telemetry as log lines and crash records and, like crashes,
 * not filtered by severity. Each entry carries the gate that decides when it may ship, so a missing
 * or renamed flag fails the build instead of silently never sending. Unfiltered means every entry
 * is a volume decision: prefer a flag that is off by default and turn it on narrow first.
 */
enum class ObservabilityEvent(
    /** The `event.name` attribute; also the record body. */
    val eventName: String,
    internal val gate: ObservabilityEventGate,
) {
    /**
     * Fired when the detector recognises the gesture, not deduped, with `gesture.result`,
     * `gesture.id_kind` and `gesture.id`. Temporary: comes out once its usage question is answered.
     */
    DEVICE_GESTURE(
        "sdk.device_gesture",
        ObservabilityEventGate.RequiresFlag(FeatureFlag.SDK_EVENT_DEVICE_GESTURE),
    ),
}
