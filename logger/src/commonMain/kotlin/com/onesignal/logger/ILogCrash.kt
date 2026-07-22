package com.onesignal.logger

/**
 * Platform-agnostic crash reporter. Persists a captured crash so it can be shipped
 * on the next launch.
 *
 * Suspend for coroutine-friendly hosts. Durability is provided by the blocking
 * [ILogFileStore.save] inside the crash telemetry sink — callers invoked from a
 * fatal handler (uncaught-exception / signal) must not return until this
 * completes (e.g. wrap in `runBlocking` on Android).
 */
interface ILogCrashReporter {
    /**
     * Records a fatal, crash-class event on the retained, disk-buffered crash telemetry
     * (`Severity.FATAL`, tagged `ossdk.crash.fatal = true`). Use for real crashes and
     * foreground ANRs.
     */
    @Throws(Exception::class)
    suspend fun saveCrash(crash: CrashData)

    /**
     * Records a non-fatal event on the same retained, disk-buffered crash telemetry, but at
     * `Severity.WARN` and tagged `ossdk.crash.fatal = false`, so it stays out of any
     * severity-based crash/ANR metric while remaining queryable. Use for backgrounded
     * main-thread blocks and other retained warnings that are not user-visible crashes.
     */
    @Throws(Exception::class)
    suspend fun saveNonFatal(crash: CrashData)
}

/**
 * Platform-agnostic crash handler. Registration of the native handler is
 * platform-specific (Android: `Thread.UncaughtExceptionHandler`), so the
 * implementation lives in the platform layer; this interface is the contract the
 * lifecycle owner uses.
 */
interface ILogCrashHandler {
    /** Installs the crash handler. Call as early as possible. */
    fun initialize()

    /** Restores the previous handler. Safe to call if never initialized. */
    fun unregister()
}

/**
 * Platform-agnostic ANR (Application Not Responding) detector. ANRs are detected
 * by monitoring main-thread responsiveness, which is platform-specific, so the
 * implementation lives in the platform layer.
 */
interface ILogAnrDetector {
    fun start()

    fun stop()
}
