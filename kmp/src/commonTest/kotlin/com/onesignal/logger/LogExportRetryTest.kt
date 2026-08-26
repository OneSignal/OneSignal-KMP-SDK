package com.onesignal.logger

import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.internal.ExportAttempt
import com.onesignal.logger.internal.ExportRetrier
import com.onesignal.logger.internal.LogTelemetryRemoteImpl
import com.onesignal.logger.internal.RetryPolicy
import com.onesignal.logger.internal.classifyStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

    /**
     * Records the virtual time at which each attempt starts, so the gaps between them are
     * the backoffs the retrier actually slept. `nextRandom = 0.5` is the midpoint of the
     * jitter spread, i.e. a factor of exactly 1.0, which makes the schedule deterministic.
     */
    private suspend fun TestScope.backoffsFor(
        policy: RetryPolicy,
        nextRandom: () -> Double = { 0.5 },
    ): List<Long> {
        val attemptTimes = mutableListOf<Long>()
        ExportRetrier(policy = policy, nextRandom = nextRandom).execute {
            attemptTimes += testScheduler.currentTime
            ExportAttempt.RETRYABLE
        }
        return attemptTimes.zipWithNext { previous, next -> next - previous }
    }

    @Test
    fun backoffFollowsTheAdvertisedSchedule() = runTest {
        // The numbers in RetryPolicy's KDoc and in the PR table are a promise; pin them.
        // 1s initial, 1.6x each time, clamped at the 5s per-delay ceiling.
        val backoffs = backoffsFor(RetryPolicy(maxAttempts = 7, maxTotalBackoffMillis = 60_000L))

        assertEquals(listOf(1_000L, 1_600L, 2_560L, 4_096L, 5_000L, 5_000L), backoffs)
    }

    @Test
    fun jitterScalesEachDelayByTheConfiguredFactor() = runTest {
        // +/-20% around the nominal delay, so a fleet backing off together re-spreads.
        val policy = RetryPolicy(maxAttempts = 3, maxTotalBackoffMillis = 60_000L)

        assertEquals(listOf(800L, 1_280L), backoffsFor(policy, nextRandom = { 0.0 }))
        assertEquals(listOf(1_200L, 1_920L), backoffsFor(policy, nextRandom = { 1.0 }))
    }

    @Test
    fun theBackoffBudgetClampsTheLastDelayAndThenStopsRetrying() = runTest {
        // The budget is charged against sleeping only, so it is exact rather than dependent
        // on how long each attempt took: 1000 + 1600 leaves 400 of a 3s budget, and the
        // fourth attempt never starts. Asserting the clamped 400 rather than just the count
        // is what stops a "return early instead of clamping" regression from passing.
        var attempts = 0
        val attemptTimes = mutableListOf<Long>()
        val succeeded =
            ExportRetrier(
                policy = RetryPolicy(maxAttempts = 10, maxTotalBackoffMillis = 3_000L),
                nextRandom = { 0.5 },
            ).execute {
                attempts++
                attemptTimes += testScheduler.currentTime
                ExportAttempt.RETRYABLE
            }

        assertFalse(succeeded)
        assertEquals(4, attempts)
        assertEquals(listOf(1_000L, 1_600L, 400L), attemptTimes.zipWithNext { a, b -> b - a })
    }

    @Test
    fun theAttemptCapHoldsNoMatterHowSlowEachAttemptIs() = runTest {
        // The bound this policy actually enforces. A wall-clock ceiling would cut this to
        // two attempts against a sender sitting on its 10s connect timeout — the slow-failure
        // case retry exists for — so the attempt count has to survive slow attempts intact.
        var attempts = 0
        ExportRetrier(policy = RetryPolicy(), nextRandom = { 0.5 }).execute {
            attempts++
            delay(10_000L) // a connect timeout, not a fast 503
            ExportAttempt.RETRYABLE
        }

        assertEquals(RetryPolicy().maxAttempts, attempts)
    }

    @Test
    fun anAbortSignalWakesAnInFlightBackoffImmediately() = runTest {
        // Teardown's lever: without it, shutdown queues behind the whole retry cycle holding
        // the export mutex, which outlasts its own flush timeout.
        val abort = CompletableDeferred<Unit>()
        var attempts = 0
        val job =
            backgroundScope.launch {
                ExportRetrier(
                    policy = RetryPolicy(maxAttempts = 10, initialBackoffMillis = 30_000L),
                    nextRandom = { 0.5 },
                ).execute(abortSignal = abort) {
                    attempts++
                    ExportAttempt.RETRYABLE
                }
            }

        runCurrent()
        assertEquals(1, attempts) // parked in a 30s backoff

        abort.complete(Unit)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(0L, testScheduler.currentTime) // returned without waiting out the backoff
        assertEquals(1, attempts)
    }

    @Test
    fun anAlreadyAbortedSignalStillLetsTheAttemptInFlightFinish() = runTest {
        // Abort stops retrying, it does not cancel work. Shutdown's own flush goes through
        // this path with the signal already completed and must still post once.
        val abort = CompletableDeferred(Unit)
        var attempts = 0

        val succeeded =
            ExportRetrier(policy = RetryPolicy(), nextRandom = { 0.5 })
                .execute(abortSignal = abort) {
                    attempts++
                    ExportAttempt.SUCCESS
                }

        assertTrue(succeeded)
        assertEquals(1, attempts)
    }

    @Test
    fun shutdownFlushesOnceWithoutEnteringARetryCycle() = runTest {
        // shutdown() blocks its caller — on Android a lifecycle thread — so its flush must
        // not be able to start a retry cycle whose backoffs outlast the flush budget.
        val http = FakeHttpSender(defaultResponse = failure(503))
        val telemetry = remote(backgroundScope, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "hello", emptyMap()))
        telemetry.shutdown()

        assertEquals(1, http.sentRequests.size)
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
    fun aRequestThatCouldNotBeBuiltIsPermanent() {
        // iOS reports -3 when URL construction fails. Retrying cannot help, and reusing the
        // -1 transport sentinel for it would burn the full budget on every batch against a
        // misconfiguration that never resolves.
        assertEquals(ExportAttempt.PERMANENT, classifyStatus(success = false, statusCode = -3))
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
