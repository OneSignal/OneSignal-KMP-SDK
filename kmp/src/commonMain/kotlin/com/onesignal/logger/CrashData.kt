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
 *   retained rather than dropped so it shows up in the data. No default on purpose: `true`
 *   would silently mark every forgotten call site as proven.
 * @property appState state of the process the crash describes, using the same vocabulary as
 *   [ILoggerPlatformProvider.appState]. Null means the live process, so a post-mortem report
 *   that could not resolve the dead process's state must pass `"unknown"`, not null.
 * @property occurredAtMs when the crash happened, epoch millis, for a report about an earlier
 *   process. Null means now. Non-positive or implausibly large values fall back to now.
 *   Reporting one of these blocks on a disk write without a dying process to justify it, so a
 *   caller replaying several must stay off the main thread.
 */
data class CrashData(
    val threadName: String,
    val exceptionType: String,
    val exceptionMessage: String,
    val stacktrace: String,
    val attributed: Boolean,
    val appState: String? = null,
    val occurredAtMs: Long? = null,
)
