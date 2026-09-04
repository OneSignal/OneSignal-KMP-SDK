package com.onesignal.logger

import com.onesignal.features.IFeatureFlagReader
import com.onesignal.features.PlatformLock
import com.onesignal.logger.internal.epochNanosNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ships [ObservabilityEvent]s as INFO records carrying `event.name` through whichever remote
 * telemetry is attached. Owns the guards so both platforms share them: the flag check, a bounded
 * queue for records made before the telemetry exists, and a per-process cap so a retry loop cannot
 * flood the endpoint. Each event names the flag that gates it, and the host only answers flag
 * lookups through [flags], so no feature manager is needed here.
 *
 * Faults log at WARN and expected drops at DEBUG, through [logger] guarded so that a host logger
 * which itself throws cannot break the never-throws contract of [record].
 */
internal class ObservabilityEventRecorder(
    private val flags: IFeatureFlagReader,
    private val logger: ILogger,
    private val maxQueued: Int = DEFAULT_MAX_QUEUED,
    private val processCap: Int = DEFAULT_PROCESS_CAP,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : IObservabilityEventRecorder {
    private val lock = PlatformLock()
    private var telemetry: ILogTelemetry? = null
    private val queued = ArrayList<LogRecord>()
    private var admitted = 0

    override fun record(event: ObservabilityEvent) = record(event, emptyMap())

    override fun record(event: ObservabilityEvent, attributes: Map<String, String>) {
        try {
            if (!event.allows(flags)) {
                debug("dropped ${event.eventName}, ${event.blockedBy()}")
                return
            }
            val record = toRecord(event, attributes)
            // Decided and, when attached, launched under the lock: launch only schedules, so the
            // lock is never held while anything runs. Only the debug line waits for it.
            val note =
                lock.withLock {
                    val current = telemetry
                    when {
                        admitted >= processCap -> "dropped ${event.eventName}, per-process cap of $processCap reached"
                        current != null -> {
                            admitted++
                            emit(current, listOf(record))
                            null
                        }
                        queued.size < maxQueued -> {
                            admitted++
                            queued.add(record)
                            "queued ${event.eventName} until remote telemetry is attached"
                        }
                        else -> "dropped ${event.eventName}, pre-attach queue is full"
                    }
                }
            note?.let(::debug)
        } catch (t: Throwable) {
            warn("failed to record ${event.eventName}: ${t.message}")
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
            debug("remote telemetry attached, flushing ${pending.size} queued event(s)")
            if (pending.isNotEmpty()) {
                emit(telemetry, pending)
            }
        } catch (t: Throwable) {
            warn("failed to attach remote telemetry: ${t.message}")
        }
    }

    override fun detach(telemetry: ILogTelemetry) {
        val detached =
            lock.withLock {
                // Equality, not identity: a Swift telemetry is wrapped anew each time it crosses into
                // Kotlin, and only equals (isEqual:) survives the crossing.
                if (this.telemetry == telemetry) {
                    this.telemetry = null
                    true
                } else {
                    false
                }
            }
        debug(if (detached) "remote telemetry detached" else "ignored a detach of telemetry that is not attached")
    }

    override fun reset() {
        val dropped =
            lock.withLock {
                val count = queued.size
                queued.clear()
                count
            }
        debug("reset, dropped $dropped queued event(s)")
    }

    private fun toRecord(event: ObservabilityEvent, attributes: Map<String, String>): LogRecord =
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
                    warn("failed to emit ${record.body}: ${e.message}")
                }
            }
        }
    }

    private fun warn(message: String) {
        try {
            logger.warn("ObservabilityEventRecorder: $message")
        } catch (_: Throwable) {
            // A throwing host logger must not turn a swallowed fault into a thrown one.
        }
    }

    private fun debug(message: String) {
        try {
            logger.debug("ObservabilityEventRecorder: $message")
        } catch (_: Throwable) {
            // Same as warn.
        }
    }

    companion object {
        /** OTel log-event convention: a record carrying this attribute is an event. */
        const val EVENT_NAME_ATTRIBUTE = "event.name"
        const val DEFAULT_MAX_QUEUED = 20
        const val DEFAULT_PROCESS_CAP = 20
    }
}
