package com.onesignal.logger

/**
 * Records named [SdkEvent]s onto the remote log pipeline. Producers call [record]; the host's
 * observability lifecycle calls [attach] and [detach] whenever it installs or drops its remote
 * telemetry, so events ride the same telemetry as log lines and crash records.
 */
interface ISdkEventRecorder {
    /**
     * Never throws or blocks, so it is safe from lifecycle callbacks on the main thread. Drops
     * when the event's flag is off or the session cap is reached; queues, bounded, until remote
     * telemetry is attached.
     */
    fun record(event: SdkEvent, attributes: Map<String, String> = emptyMap())

    /** Installs or replaces the remote telemetry, then flushes anything queued. */
    fun attach(telemetry: ILogTelemetry)

    /** Later records queue, bounded, until the next [attach]. */
    fun detach()
}
