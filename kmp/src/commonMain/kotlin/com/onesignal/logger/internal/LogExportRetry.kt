package com.onesignal.logger.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

/** Classification of a single export attempt, driving the retry decision. */
internal enum class ExportAttempt {
    SUCCESS,

    /** Transient: worth another attempt after a backoff (5xx-ish, throttling, transport). */
    RETRYABLE,

    /** Permanent: the same request will keep failing, so retrying only wastes battery. */
    PERMANENT,
}

/**
 * Bounds for [ExportRetrier]. Defaults mirror what OpenTelemetry's okhttp sender applied
 * by default before it was removed: 5 attempts, 1s initial backoff growing by 1.6x up to
 * 5s, 20% jitter.
 *
 * [maxTotalBackoffMillis] bounds *sleeping only* — see [ExportRetrier] for why there is no
 * wall-clock bound. At the default attempt count the schedule already sums to ~9.3s
 * (1 + 1.6 + 2.56 + 4.096, ±20% jitter), so this ceiling binds only if [maxAttempts] is
 * raised; it exists so that raising it cannot silently produce an unbounded sleep.
 */
internal data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialBackoffMillis: Long = 1_000L,
    val maxBackoffMillis: Long = 5_000L,
    val backoffMultiplier: Double = 1.6,
    val jitterFactor: Double = 0.2,
    val maxTotalBackoffMillis: Long = 15_000L,
)

/** Statuses OpenTelemetry's `RetryUtil` treated as retryable. */
private val RETRYABLE_STATUS_CODES = setOf(429, 502, 503, 504)

/**
 * Sentinel both platform senders already report for a transport-level failure
 * (no HTTP response at all): DNS, connect/read timeout, socket reset.
 *
 * This means "the request went out and nothing usable came back", which is worth retrying.
 * A failure to *build* the request is not — it will fail identically every time — so senders
 * must report that separately (iOS uses -3) and let it fall through to permanent below.
 */
internal const val TRANSPORT_FAILURE_STATUS_CODE = -1

internal fun classifyStatus(
    success: Boolean,
    statusCode: Int,
): ExportAttempt =
    when {
        success -> ExportAttempt.SUCCESS
        statusCode == TRANSPORT_FAILURE_STATUS_CODE -> ExportAttempt.RETRYABLE
        statusCode in RETRYABLE_STATUS_CODES -> ExportAttempt.RETRYABLE
        // Everything else — 4xx, and the -2 "remote logging disabled" sentinel — is permanent.
        else -> ExportAttempt.PERMANENT
    }

/**
 * Retries a single export with exponential backoff and jitter, bounded by an attempt count
 * and by the total time spent *sleeping between* attempts.
 *
 * ### There is no wall-clock bound, on purpose
 *
 * This retrier cannot cancel a send once [execute]'s `attempt` is running — `ILogHttpSender`
 * takes no deadline and neither platform implementation accepts one. A wall-clock ceiling
 * would therefore only ever be checked between attempts, which does not bound anything: an
 * attempt starting just inside the budget still runs to the sender's own timeout (10s on
 * both platforms). Worse, it under-delivers precisely where retry matters most — against
 * connect timeouts, a 15s ceiling with 10s timeouts yields ~2 attempts, not the advertised 5.
 *
 * So the attempt count is the real bound and it is honored regardless of how slow each
 * attempt is. Stated plainly, the worst case for one export is
 * `maxAttempts × senderTimeout + maxTotalBackoffMillis` — with today's defaults and a 10s
 * sender timeout, ~59s of a *background* export coroutine, with no caller blocked on it.
 *
 * Callers that do need a prompt exit — teardown, in particular — pass an `abortSignal`:
 * completing it wakes an in-flight backoff immediately and stops further attempts, so the
 * wait collapses to at most the attempt already in flight rather than the full cycle.
 *
 * Waiting is suspension, never a blocking sleep, so a cancelled scope unwinds promptly and
 * `CancellationException` propagates to the caller rather than being swallowed.
 *
 * [nextRandom] is injectable purely so tests can pin the jitter; production uses
 * [Random.Default].
 */
internal class ExportRetrier(
    private val policy: RetryPolicy = RetryPolicy(),
    private val nextRandom: () -> Double = { Random.nextDouble() },
) {
    /**
     * Runs [attempt] until it succeeds, fails permanently, or the bounds are reached.
     * Completing [abortSignal] cuts a backoff short and prevents further attempts; the
     * attempt already in flight is left to finish.
     *
     * Returns true only if [attempt] ultimately reported success.
     */
    suspend fun execute(
        abortSignal: Deferred<Unit> = CompletableDeferred(),
        attempt: suspend () -> ExportAttempt,
    ): Boolean {
        var attemptsMade = 0
        var backoffMillis = policy.initialBackoffMillis
        var backoffSpentMillis = 0L

        while (true) {
            attemptsMade++
            when (attempt()) {
                ExportAttempt.SUCCESS -> return true
                ExportAttempt.PERMANENT -> return false
                ExportAttempt.RETRYABLE -> Unit
            }

            if (attemptsMade >= policy.maxAttempts) return false
            if (abortSignal.isCompleted) return false

            val remainingBackoffMillis = policy.maxTotalBackoffMillis - backoffSpentMillis
            if (remainingBackoffMillis <= 0) return false

            val waitMillis = min(jittered(backoffMillis), remainingBackoffMillis)
            backoffSpentMillis += waitMillis
            backoffMillis = min((backoffMillis * policy.backoffMultiplier).toLong(), policy.maxBackoffMillis)

            // Sleep for the backoff, but wake early if the caller aborts.
            val aborted = withTimeoutOrNull(waitMillis) { abortSignal.await() } != null
            if (aborted) return false
        }
    }

    /** Spreads the backoff over +/- [RetryPolicy.jitterFactor] so clients do not sync up. */
    private fun jittered(backoffMillis: Long): Long {
        val spread = 1.0 - policy.jitterFactor + (2.0 * policy.jitterFactor * nextRandom())
        return (backoffMillis * spread).toLong().coerceAtLeast(0L)
    }
}
