package com.onesignal.logger

import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.internal.LogTelemetryRemoteImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LogLoggingHelperTest {
    @Test
    fun plainLogCarriesItsMessageAndLevel() = runTest {
        val telemetry = RecordingTelemetry()

        LogLoggingHelper.log(telemetry, level = "ERROR", message = "boom")

        val record = telemetry.emitted.single()
        assertEquals("boom", record.body)
        assertEquals(LogSeverity.ERROR, record.severity)
        assertEquals(mapOf("log.message" to "boom", "log.level" to "ERROR"), record.attributes)
    }

    @Test
    fun verboseKeepsItsLevelEvenThoughSeverityIsTrace() = runTest {
        // The ingestion service buckets severity numbers 1..8 into DEBUG and ignores
        // severity_text, so log.level is the only thing separating VERBOSE from DEBUG there.
        val telemetry = RecordingTelemetry()

        LogLoggingHelper.log(telemetry, level = "VERBOSE", message = "trace me")

        val record = telemetry.emitted.single()
        assertEquals(LogSeverity.TRACE, record.severity)
        assertEquals("VERBOSE", record.attributes["log.level"])
    }

    @Test
    fun exceptionDetailsStayInAttributes() = runTest {
        val telemetry = RecordingTelemetry()

        LogLoggingHelper.log(
            telemetry,
            level = "WARN",
            message = "failed to send",
            exceptionType = "java.io.IOException",
            exceptionMessage = "timeout",
            exceptionStacktrace = "stack...",
        )

        val record = telemetry.emitted.single()
        assertEquals(
            mapOf(
                "log.message" to "failed to send",
                "log.level" to "WARN",
                "exception.type" to "java.io.IOException",
                "exception.message" to "timeout",
                "exception.stacktrace" to "stack...",
            ),
            record.attributes,
        )
    }

    @Test
    fun aThrowableWithoutAMessageOmitsTheExceptionMessageAttribute() = runTest {
        val telemetry = RecordingTelemetry()

        LogLoggingHelper.log(
            telemetry,
            level = "ERROR",
            message = "failed to send",
            exceptionType = "java.lang.NullPointerException",
            exceptionStacktrace = "stack...",
        )

        val record = telemetry.emitted.single()
        assertEquals(
            mapOf(
                "log.message" to "failed to send",
                "log.level" to "ERROR",
                "exception.type" to "java.lang.NullPointerException",
                "exception.stacktrace" to "stack...",
            ),
            record.attributes,
        )
    }

    @Test
    fun levelAndMessageReachTheWireAsAttributes() = runTest {
        // The cases above stop at LogRecord; only this one proves the per-event fields
        // and the encoder still carry both keys onto an exported record.
        val provider = FakePlatformProvider()
        val http = FakeHttpSender()
        val telemetry =
            LogTelemetryRemoteImpl(
                platformProvider = provider,
                httpSender = http,
                topLevelFields = LogFieldsTopLevel(provider),
                perEventFields = LogFieldsPerEvent(provider),
                scope = backgroundScope,
            )

        LogLoggingHelper.log(telemetry, level = "VERBOSE", message = "trace me")
        telemetry.forceFlush()

        val record = parseProto(http.sentRequests.single().body).message(1).message(2).message(2)
        val attrs =
            record.all(6)
                .map { parseProto(it.bytes()) }
                .associate { it.string(1) to it.message(2).string(1) }
        assertEquals("trace me", attrs["log.message"])
        assertEquals("VERBOSE", attrs["log.level"])
        assertEquals("trace me", record.message(5).string(1))
    }
}
