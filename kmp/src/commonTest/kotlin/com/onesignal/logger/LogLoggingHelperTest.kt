package com.onesignal.logger

import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.internal.LogTelemetryRemoteImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogLoggingHelperTest {
    @Test
    fun plainLogCarriesMessageAndLevelOnlyOnBodyAndSeverity() = runTest {
        val telemetry = RecordingTelemetry()

        LogLoggingHelper.log(telemetry, level = "ERROR", message = "boom")

        val record = telemetry.emitted.single()
        assertEquals("boom", record.body)
        assertEquals(LogSeverity.ERROR, record.severity)
        assertTrue(record.attributes.isEmpty())
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
                "exception.type" to "java.lang.NullPointerException",
                "exception.stacktrace" to "stack...",
            ),
            record.attributes,
        )
    }

    @Test
    fun neitherMessageNorLevelReachesTheWireAsAnAttribute() = runTest {
        // The helper-level tests stop at LogRecord; only this one proves the per-event
        // fields and the encoder do not put either key back on an exported record.
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

        LogLoggingHelper.log(telemetry, level = "ERROR", message = "boom")
        telemetry.forceFlush()

        val record = parseProto(http.sentRequests.single().body).message(1).message(2).message(2)
        val attrKeys = record.all(6).map { parseProto(it.bytes()).string(1) }
        assertFalse("log.message" in attrKeys)
        assertFalse("log.level" in attrKeys)
        // The level and message survive as the standard fields instead.
        assertEquals("ERROR", record.string(3))
        assertEquals("boom", record.message(5).string(1))
    }
}
