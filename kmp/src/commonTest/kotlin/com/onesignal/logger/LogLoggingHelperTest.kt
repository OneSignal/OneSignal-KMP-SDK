package com.onesignal.logger

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
