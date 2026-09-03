package com.onesignal.logger

import com.onesignal.features.PlatformLock
import com.onesignal.logger.internal.epochNanosNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ships named [SdkEvent]s as INFO records carrying `event.name` through whichever remote
 * telemetry is attached. Owns the guards so both platforms share them: the flag check, a bounded
 * queue for records made before the telemetry exists, and a per-process cap so a retry loop cannot
 * flood the endpoint. [isEnabled] is a function rather than a feature manager so this package does not
 * depend on the features package at runtime.
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
        class Emit(val telemetry: ILogTelemetry) : Admission

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
                is Admission.Emit -> emit(admission.telemetry, listOf(record))
                Admission.Queued -> logger.debug("LogEventRecorder: queued ${event.eventName} until remote telemetry is attached")
                Admission.QueueFull -> logger.debug("LogEventRecorder: dropped ${event.eventName}, pre-attach queue is full")
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
                logger.debug("LogEventRecorder: remote telemetry attached, flushing ${pending.size} queued event(s)")
                emit(telemetry, pending)
            }
        } catch (t: Throwable) {
            logger.debug("LogEventRecorder: failed to attach remote telemetry: ${t.message}")
        }
    }

    override fun detach() {
        lock.withLock { telemetry = null }
    }

    /** Kept separate so the lock covers the decision only, not the emit. */
    private fun admit(record: LogRecord): Admission =
        lock.withLock {
            val current = telemetry
            when {
                admitted >= sessionCap -> Admission.CapReached
                current != null -> {
                    admitted++
                    Admission.Emit(current)
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
            // Stamped now so a queued event keeps the time it happened, not the flush time.
            timestampNanos = epochNanosNow(),
        )

    /** One coroutine per call so a flush keeps its recording order. */
    private fun emit(telemetry: ILogTelemetry, records: List<LogRecord>) {
        scope.launch {
            for (record in records) {
                try {
                    telemetry.emit(record)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("LogEventRecorder: failed to emit ${record.body}: ${e.message}")
                }
            }
        }
    }

    companion object {
        /** OTel log-event convention: a record carrying this attribute is an event. */
        const val EVENT_NAME_ATTRIBUTE = "event.name"
        const val DEFAULT_MAX_QUEUED = 20
        const val DEFAULT_SESSION_CAP = 20
    }
}
