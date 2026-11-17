package com.onesignal.logger

/**
 * Platform-neutral description of a crash.
 *
 * The old `otel` module took a JVM `Thread` + `Throwable` directly, which is not
 * expressible in `commonMain`. Crash *capture* is inherently platform-specific
 * (Android: `Thread.UncaughtExceptionHandler`; iOS: signal/NSException handlers),
 * so the platform is responsible for translating its native crash representation
 * into this neutral shape before handing it to [ILogCrashReporter].
 *
 * @property attributed false when the platform could not prove the crash is ours, which is
 *   retained rather than dropped so it shows up in the data. No default, on purpose.
 */
data class CrashData(
    val threadName: String,
    val exceptionType: String,
    val exceptionMessage: String,
    val stacktrace: String,
    val attributed: Boolean,
)
