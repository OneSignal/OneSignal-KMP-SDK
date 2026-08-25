package com.onesignal.logger

import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.internal.ExportAttempt
import com.onesignal.logger.internal.ExportRetrier
import com.onesignal.logger.internal.LogTelemetryRemoteImpl
import com.onesignal.logger.internal.RetryPolicy
import com.onesignal.logger.internal.classifyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LogExportRetryTest {
    private fun remote(
        scope: CoroutineScope,
        http: FakeHttpSender,
    ): LogTelemetryRemoteImpl {
        val provider = FakePlatformProvider()
        return LogTelemetryRemoteImpl(
            platformProvider = provider,
            httpSender = http,
            topLevelFields = LogFieldsTopLevel(provider),
            perEventFields = LogFieldsPerEvent(provider),
            scope = scope,
        )
    }

    private fun failure(statusCode: Int) = LogHttpResponse(success = false, statusCode = statusCode)

    private val ok = LogHttpResponse(success = true, statusCode = 200)

    @Test
    fun happyPathPostsExactlyOnce() = runTest {
        val http = FakeHttpSender()
        val telemetry = remote(backgroundScope, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "hello", emptyMap()))
        telemetry.forceFlush()

        assertEquals(1, http.sentRequests.size)
    }

    @Test
    fun retryableStatusIsRetriedUntilSuccess() = runTest {
        val http = FakeHttpSender(responses = ArrayDeque(listOf(failure(503), failure(429), ok)))
        val telemetry = remote(backgroundScope, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "hello", emptyMap()))
        telemetry.forceFlush()

        assertEquals(3, http.sentRequests.size)
        // Every attempt re-posts the same batch, not a truncated or re-encoded one.
        assertEquals(http.sentRequests[0].body.size, http.sentRequests[2].body.size)
    }

    @Test
    fun transportFailureIsRetried() = runTest {
        val http =
            FakeHttpSender(
                responses = ArrayDeque(listOf(failure(-1), ok)),
                exceptions = ArrayDeque(listOf(RuntimeException("connection reset"))),
            )
        val telemetry = remote(backgroundScope, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "hello", emptyMap()))
        telemetry.forceFlush()

        // Thrown send, then statusCode -1, then success.
        assertEquals(3, http.sentRequests.size)
    }

    @Test
    fun permanentStatusIsNotRetried() = runTest {
        val http = FakeHttpSender(defaultResponse = failure(400))
        val telemetry = remote(backgroundScope, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "hello", emptyMap()))
        telemetry.forceFlush()

        assertEquals(1, http.sentRequests.size)
    }

    @Test
    fun attemptCapIsHonoredWhenBackendKeepsFailing() = runTest {
        val http = FakeHttpSender(defaultResponse = failure(503))
        val telemetry = remote(backgroundScope, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "hello", emptyMap()))
        telemetry.forceFlush()

        assertEquals(RetryPolicy().maxAttempts, http.sentRequests.size)
    }

    @Test
    fun elapsedCapStopsRetriesBeforeAttemptCap() = runTest {
        var clock = 0L
        val retrier =
            ExportRetrier(
                policy = RetryPolicy(maxAttempts = 10, maxElapsedMillis = 15_000L),
                nowMillis = { clock.also { clock += 10_000L } },
                nextRandom = { 0.5 },
            )
        var attempts = 0

        val succeeded =
            retrier.execute {
                attempts++
                ExportAttempt.RETRYABLE
            }

        assertFalse(succeeded)
        assertTrue(attempts < 10, "elapsed cap should stop retries early, got $attempts attempts")
    }

    @Test
    fun cancellationDuringBackoffDelayPropagates() = runTest {
        var attempts = 0
        val retrier =
            ExportRetrier(
                policy = RetryPolicy(maxAttempts = 10),
                nowMillis = { 0L },
                nextRandom = { 0.5 },
            )
        val job =
            backgroundScope.launch {
                retrier.execute {
                    attempts++
                    ExportAttempt.RETRYABLE
                }
            }

        runCurrent()
        assertEquals(1, attempts) // parked in the first backoff delay

        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, attempts) // never woke up for another attempt
    }

    @Test
    fun classifiesStatusCodes() {
        assertEquals(ExportAttempt.SUCCESS, classifyStatus(success = true, statusCode = 200))
        listOf(429, 502, 503, 504, -1).forEach {
            assertEquals(ExportAttempt.RETRYABLE, classifyStatus(success = false, statusCode = it), "status $it")
        }
        listOf(400, 401, 403, 404, 500, -2).forEach {
            assertEquals(ExportAttempt.PERMANENT, classifyStatus(success = false, statusCode = it), "status $it")
        }
    }
}
