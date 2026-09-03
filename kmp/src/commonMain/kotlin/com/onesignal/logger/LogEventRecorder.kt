package com.onesignal.logger

import com.onesignal.features.PlatformLock
import com.onesignal.logger.internal.epochNanosNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ships named [SdkEvent]s as INFO log records carrying `event.name`, through whichever
 * [ILogTelemetry] is attached. The recorder owns the guards on events, so they are written and
 * tested once for both platforms:
 *
 * - The flag check. [isEnabled] is the platform's feature-manager read for [SdkEvent.flag],
 *   taken as a function so this package stays free of the features package at runtime.
 * - The pre-sink queue. Before HYDRATE installs the sink (first launch, params failure), up to
 *   [maxQueued] records wait in memory and [attach] flushes them. Dropped at process end.
 * - The session cap. At most [sessionCap] events ship per process, so a retry loop cannot
 *   flood the log endpoint. A backstop, not a budget.
 *
 * [record] never suspends, waits on I/O or throws: it runs from lifecycle callbacks on the main
 * thread. Emission hops to [scope]; the queue and counters sit behind [lock].
 */
internal class LogEventRecorder(
    private val scope: CoroutineScope,
    private val isEnabled: (SdkEvent) -> Boolean,
    private val logger: ILogger,
    private val maxQueued: Int = DEFAULT_MAX_QUEUED,
    private val sessionCap: Int = DEFAULT_SESSION_CAP,
) : ISdkEventRecorder {
    private val lock = PlatformLock()
    private var telemetry: ILogTelemetry? = null
    private val queued = ArrayList<LogRecord>()
    private var admitted = 0

    private sealed interface Admission {
        class Emit(val sink: ILogTelemetry) : Admission

        object Queued : Admission

        object QueueFull : Admission

        object CapReached : Admission
    }

    override fun record(event: SdkEvent, attributes: Map<String, String>) {
        try {
            if (!isEnabled(event)) {
                logger.debug("LogEventRecorder: dropped ${event.eventName}, ${event.flag.key} is off")
                return
            }
            val record = toRecord(event, attributes)
            when (val admission = admit(record)) {
                is Admission.Emit -> emit(admission.sink, listOf(record))
                Admission.Queued -> logger.debug("LogEventRecorder: queued ${event.eventName} until a sink is attached")
                Admission.QueueFull -> logger.debug("LogEventRecorder: dropped ${event.eventName}, pre-sink queue is full")
                Admission.CapReached ->
                    logger.debug("LogEventRecorder: dropped ${event.eventName}, session cap of $sessionCap reached")
            }
        } catch (t: Throwable) {
            logger.debug("LogEventRecorder: failed to record ${event.eventName}: ${t.message}")
        }
    }

    override fun attach(telemetry: ILogTelemetry) {
        try {
            val pending =
                lock.withLock {
                    this.telemetry = telemetry
                    val copy = queued.toList()
                    queued.clear()
                    copy
                }
            if (pending.isNotEmpty()) {
                logger.debug("LogEventRecorder: sink attached, flushing ${pending.size} queued event(s)")
                emit(telemetry, pending)
            }
        } catch (t: Throwable) {
            logger.debug("LogEventRecorder: failed to attach the sink: ${t.message}")
        }
    }

    override fun detach() {
        lock.withLock { telemetry = null }
    }

    /** Applies the cap and the queue under [lock]; only an [Admission.Emit] needs work outside it. */
    private fun admit(record: LogRecord): Admission =
        lock.withLock {
            val sink = telemetry
            when {
                admitted >= sessionCap -> Admission.CapReached
                sink != null -> {
                    admitted++
                    Admission.Emit(sink)
                }
                queued.size < maxQueued -> {
                    admitted++
                    queued.add(record)
                    Admission.Queued
                }
                else -> Admission.QueueFull
            }
        }

    private fun toRecord(event: SdkEvent, attributes: Map<String, String>): LogRecord =
        LogRecord(
            severity = LogSeverity.INFO,
            body = event.eventName,
            // The event name goes last so a caller attribute cannot shadow it.
            attributes = attributes + (EVENT_NAME_ATTRIBUTE to event.eventName),
            // Stamped at record time, so a queued event keeps the time it happened rather than
            // the time the sink came up.
            timestampNanos = epochNanosNow(),
        )

    /** One coroutine per call, so the records of a flush leave in the order they were recorded. */
    private fun emit(sink: ILogTelemetry, records: List<LogRecord>) {
        scope.launch {
            for (record in records) {
                try {
                    sink.emit(record)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("LogEventRecorder: failed to emit ${record.body}: ${e.message}")
                }
            }
        }
    }

    companion object {
        /** OTel log-event convention. A record carrying this attribute is an event. */
        const val EVENT_NAME_ATTRIBUTE = "event.name"
        const val DEFAULT_MAX_QUEUED = 20
        const val DEFAULT_SESSION_CAP = 20
    }
}
