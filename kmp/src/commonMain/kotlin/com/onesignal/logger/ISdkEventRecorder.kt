package com.onesignal.logger

/**
 * Records named [SdkEvent]s onto the remote log pipeline.
 *
 * Producers call [record]. The platform's observability lifecycle owns [attach] and [detach],
 * calling them beside its own `Logging` sink swap so events ride the sink `Logging.*` lines and
 * crash records use. Obtain one from [LoggerFactory.createEventRecorder].
 */
interface ISdkEventRecorder {
    /**
     * Records [event] with the caller's [attributes]. Fail-open and non-blocking: safe from any
     * thread, including lifecycle callbacks on the main thread, and never throws. The record
     * drops when the event's flag is off or the session cap is reached, and queues, bounded,
     * while no sink is attached.
     */
    fun record(event: SdkEvent, attributes: Map<String, String> = emptyMap())

    /** Installs or replaces the sink events ship through, then flushes anything queued. */
    fun attach(telemetry: ILogTelemetry)

    /** Removes the sink. Later records queue again, bounded, until the next [attach]. */
    fun detach()
}
