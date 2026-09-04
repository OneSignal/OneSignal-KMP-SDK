package com.onesignal.logger

/**
 * Records [ObservabilityEvent]s onto the remote log pipeline. Producers call [record]; the host's
 * observability lifecycle calls [attach] and [detach] whenever it installs or drops its remote
 * telemetry, so events ride the same telemetry as log lines and crash records.
 */
interface IObservabilityEventRecorder {
    /**
     * Never throws or blocks, so it is safe from lifecycle callbacks on the main thread. Drops
     * when the event's gate says no or the per-process cap is reached; queues, bounded, until
     * remote telemetry is attached.
     */
    fun record(event: ObservabilityEvent, attributes: Map<String, String>)

    /** [record] with no attributes beyond `event.name`. */
    fun record(event: ObservabilityEvent)

    /** Installs or replaces the remote telemetry, then flushes anything queued. */
    fun attach(telemetry: ILogTelemetry)

    /**
     * Removes [telemetry] only if it is the attached one. A host that shares one recorder across
     * telemetry instances shuts down instances that never won the install, and that must not
     * detach the one that did.
     */
    fun detach(telemetry: ILogTelemetry)

    /** Drops every queued record, for an app-id change. The attached telemetry and the cap stay. */
    fun reset()
}
