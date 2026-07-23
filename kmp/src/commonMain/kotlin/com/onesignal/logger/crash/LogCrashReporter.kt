package com.onesignal.logger.crash

import com.onesignal.logger.CrashData
import com.onesignal.logger.ILogCrashReporter
import com.onesignal.logger.ILogTelemetryCrash
import com.onesignal.logger.ILogger
import com.onesignal.logger.LogRecord
import com.onesignal.logger.LogSeverity
import kotlinx.coroutines.runBlocking

/**
 * Persists a captured crash by emitting it to the crash (disk) telemetry sink.
 *
 * Mirrors `OtelCrashReporter`, but takes a platform-neutral [CrashData] instead of a
 * JVM `Thread`/`Throwable`. Public [saveCrash]/[saveNonFatal] are synchronous for
 * fatal-handler call sites; [runBlocking] here bridges to the suspend telemetry sink
 * (needed only for [ILoggerPlatformProvider.getInstallId]). [forceFlush] on the crash
 * sink is a no-op — the disk write already completed in [ILogTelemetryCrash.emit].
 */
internal class LogCrashReporter(
    private val crashTelemetry: ILogTelemetryCrash,
    private val logger: ILogger,
) : ILogCrashReporter {
    override fun saveCrash(crash: CrashData) = save(crash, severity = LogSeverity.FATAL, fatal = true)

    override fun saveNonFatal(crash: CrashData) = save(crash, severity = LogSeverity.WARN, fatal = false)

    private fun save(
        crash: CrashData,
        severity: LogSeverity,
        fatal: Boolean,
    ) {
        val label = if (fatal) "crash report" else "non-fatal report"
        logger.info("LogCrashReporter: saving $label for ${crash.exceptionType}")

        val body = crash.exceptionMessage.ifBlank { crash.exceptionType }
        val record =
            LogRecord(
                severity = severity,
                body = body,
                attributes =
                mapOf(
                    "exception.message" to crash.exceptionMessage,
                    "exception.stacktrace" to crash.stacktrace,
                    "exception.type" to crash.exceptionType,
                    // Matches the top-level thread.name today, but kept distinct in
                    // case future refactors report from a different thread.
                    "ossdk.exception.thread.name" to crash.threadName,
                ),
                // Explicit, SDK-owned fatal flag emitted as a typed OTLP bool. The backend can
                // segment crash/ANR metrics on this stable attribute rather than inferring intent
                // from severity or exception.type alone, so a non-fatal record can never be
                // double-counted as a crash even if a mapping changes.
                boolAttributes = mapOf(OSSDK_CRASH_FATAL to fatal),
            )

        try {
            runBlocking {
                crashTelemetry.emit(record)
                crashTelemetry.forceFlush()
            }
            logger.info("LogCrashReporter: $label saved and flushed")
        } catch (e: Exception) {
            logger.error("LogCrashReporter: failed to save $label: ${e.message}")
            throw e
        }
    }

    private companion object {
        const val OSSDK_CRASH_FATAL = "ossdk.crash.fatal"
    }
}
