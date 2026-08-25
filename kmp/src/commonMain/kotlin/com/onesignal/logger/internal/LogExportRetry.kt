package com.onesignal.logger.internal

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.TimeSource

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
 * by default before it was removed (5 attempts, 1s initial backoff growing by 1.6x up to
 * 5s, 20% jitter), plus an elapsed-time ceiling OTel did not have.
 */
internal data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialBackoffMillis: Long = 1_000L,
    val maxBackoffMillis: Long = 5_000L,
    val backoffMultiplier: Double = 1.6,
    val jitterFactor: Double = 0.2,
    val maxElapsedMillis: Long = 15_000L,
)

/** Statuses OpenTelemetry's `RetryUtil` treated as retryable. */
private val RETRYABLE_STATUS_CODES = setOf(429, 502, 503, 504)

/**
 * Sentinel both platform senders already report for a transport-level failure
 * (no HTTP response at all): DNS, connect/read timeout, socket reset.
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

private val processStart = TimeSource.Monotonic.markNow()

/**
 * Retries a single export with exponential backoff and jitter, bounded by both an
 * attempt count and total elapsed time.
 *
 * Waiting uses [delay], so a cancelled scope unwinds promptly and
 * `CancellationException` propagates to the caller rather than being swallowed.
 *
 * [nowMillis] and [nextRandom] are injectable purely so tests can drive the clock and
 * remove jitter; production always uses a monotonic clock and [Random.Default].
 */
internal class ExportRetrier(
    private val policy: RetryPolicy = RetryPolicy(),
    private val nowMillis: () -> Long = { processStart.elapsedNow().inWholeMilliseconds },
    private val nextRandom: () -> Double = { Random.nextDouble() },
) {
    /** Returns true only if [attempt] ultimately reported success. */
    suspend fun execute(attempt: suspend () -> ExportAttempt): Boolean {
        val start = nowMillis()
        var attemptsMade = 0
        var backoffMillis = policy.initialBackoffMillis

        while (true) {
            attemptsMade++
            when (attempt()) {
                ExportAttempt.SUCCESS -> return true
                ExportAttempt.PERMANENT -> return false
                ExportAttempt.RETRYABLE -> Unit
            }

            if (attemptsMade >= policy.maxAttempts) return false

            val remainingMillis = policy.maxElapsedMillis - (nowMillis() - start)
            if (remainingMillis <= 0) return false

            delay(min(jittered(backoffMillis), remainingMillis))
            backoffMillis = min((backoffMillis * policy.backoffMultiplier).toLong(), policy.maxBackoffMillis)
        }
    }

    /** Spreads the backoff over +/- [RetryPolicy.jitterFactor] so clients do not sync up. */
    private fun jittered(backoffMillis: Long): Long {
        val spread = 1.0 - policy.jitterFactor + (2.0 * policy.jitterFactor * nextRandom())
        return (backoffMillis * spread).toLong().coerceAtLeast(0L)
    }
}
