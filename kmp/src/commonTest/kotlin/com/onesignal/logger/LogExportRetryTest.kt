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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        // Every attempt re-posts the same batch, not a truncated or re-encoded one. Comparing
        // lengths would pass for any two distinct payloads that happen to be the same size,
        // including a re-encode of the same records.
        assertTrue(http.sentRequests[0].body.contentEquals(http.sentRequests[2].body))
    }

    // The crash path must stay single-shot. LogCrashUploader already retries across launches
    // and stops on first failure, so routing exportEncoded through the retrier would give
    // crash records 5 in-process attempts on top of that. These pin the split, which is
    // otherwise invisible: every pre-existing test uses status 500, which classifies as
    // PERMANENT and yields one attempt either way.

    @Test
    fun exportEncodedIsNotRetriedOnARetryableStatus() = runTest {
        val http = FakeHttpSender(responses = ArrayDeque(listOf(failure(503), ok)))
        val telemetry = remote(backgroundScope, http)

        val succeeded = telemetry.exportEncoded(byteArrayOf(1, 2, 3))

        assertFalse(succeeded)
        assertEquals(1, http.sentRequests.size)
    }

    @Test
    fun exportEncodedIsNotRetriedWhenTheSenderThrows() = runTest {
        val http =
            FakeHttpSender(
                responses = ArrayDeque(listOf(ok)),
                exceptions = ArrayDeque(listOf(RuntimeException("socket reset"))),
            )
        val telemetry = remote(backgroundScope, http)

        // A thrown sender maps to RETRYABLE on the batched path. The crash path must not
        // reinterpret it that way — it surfaces to LogCrashUploader, which stops on first
        // failure and keeps the record for the next launch.
        assertFailsWith<RuntimeException> {
            telemetry.exportEncoded(byteArrayOf(1, 2, 3))
        }

        assertEquals(1, http.sentRequests.size)
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
        // The clock is advanced explicitly by the fake sender, not by the act of reading it,
        // so the outcome depends on elapsed time rather than on how many times the retrier
        // happens to call nowMillis(). An assertion of "fewer than maxAttempts" would pass at
        // 9 attempts — i.e. against a nearly-broken ceiling — so pin the exact count.
        var clock = 0L
        val retrier =
            ExportRetrier(
                policy = RetryPolicy(maxAttempts = 10, maxElapsedMillis = 15_000L),
                nowMillis = { clock },
                nextRandom = { 0.5 },
            )
        var attempts = 0

        val succeeded =
            retrier.execute {
                attempts++
                clock += 8_000L // each attempt burns 8s of the 15s budget
                ExportAttempt.RETRYABLE
            }

        assertFalse(succeeded)
        // Attempt 1 ends at 8s (7s left, retry). Attempt 2 ends at 16s, over budget.
        assertEquals(2, attempts)
    }

    @Test
    fun elapsedCapStopsBeforeStartingAnotherAttemptAfterBackoff() = runTest {
        // Checking the budget only before the delay bounds when the *wait* may start, not
        // when the work may start. A sender sitting on its own timeout could then push total
        // elapsed far past the ceiling while the caller is blocked on it.
        //
        // The clock has to include virtual time or the backoff consumes no budget and the
        // re-check is meaningless: `burned` is what each attempt costs, `currentTime` is what
        // the delays cost.
        var burned = 0L
        val retrier =
            ExportRetrier(
                policy = RetryPolicy(maxAttempts = 10, maxElapsedMillis = 15_000L, initialBackoffMillis = 1_000L),
                nowMillis = { burned + testScheduler.currentTime },
                nextRandom = { 0.5 },
            )
        var attempts = 0

        retrier.execute {
            attempts++
            burned += 14_500L // leaves 500ms, which the backoff then consumes entirely
            ExportAttempt.RETRYABLE
        }

        assertEquals(1, attempts)
    }

    @Test
    fun aCancelledSenderIsNotReclassifiedAsATransientFailure() = runTest {
        // Pins the `catch (e: CancellationException) { throw e }` in attemptPost, which reads
        // as redundant next to the catch-all below it. It is not: CancellationException is an
        // Exception in Kotlin, so without it a cancelled send is classified RETRYABLE and the
        // retrier keeps posting — burning the full attempt budget on a scope that is already
        // going away, and losing the cancellation entirely on the paths that return before
        // the next delay().
        //
        // This has to go through the real telemetry rather than a bare retrier: passing a
        // throwing lambda straight to ExportRetrier.execute bypasses attemptPost, so such a
        // test passes whether or not the guard exists.
        val http =
            FakeHttpSender(
                exceptions = ArrayDeque(listOf(CancellationException("scope cancelled mid-send"))),
            )
        val telemetry = remote(backgroundScope, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "hello", emptyMap()))
        assertFailsWith<CancellationException> { telemetry.forceFlush() }

        // One send, not maxAttempts: cancellation stopped the loop instead of feeding it.
        assertEquals(1, http.sentRequests.size)
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
