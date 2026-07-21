package com.onesignal.logger

/**
 * Platform-agnostic telemetry sink. Analogue of `IOtelOpenTelemetry`, but the
 * public surface only uses plain Kotlin types (no OpenTelemetry types leak out).
 */
interface ILogTelemetry {
    /**
     * Records a single log record. For the remote sink this enqueues the record
     * into the batch processor; for the crash sink this writes it to disk.
     */
    @Throws(Exception::class)
    suspend fun emit(record: LogRecord)

    /** Forces all pending records to be exported/persisted immediately. */
    @Throws(Exception::class)
    suspend fun forceFlush()

    /**
     * Shuts down the sink, flushing pending data and releasing resources. After
     * this call the instance must not be reused.
     */
    fun shutdown()
}

/** Telemetry sink that ships records over the network (batched). */
interface ILogTelemetryRemote : ILogTelemetry {
    /**
     * POSTs a pre-encoded payload immediately, bypassing the batch queue, and
     * returns whether the export succeeded. Used by the crash uploader to ship
     * disk-buffered crash records (which are stored already-encoded) on the next
     * launch, preserving their original capture-time resource attributes.
     */
    @Throws(Exception::class)
    suspend fun exportEncoded(payload: ByteArray): Boolean
}

/**
 * Telemetry sink that persists records to local storage (crash buffering).
 *
 * Intentionally synchronous (no `suspend`): the crash reporter calls this from an
 * uncaught-exception / signal handler and must finish the disk write before the
 * process dies. On the Obj-C/Swift boundary a `suspend` API would become async and
 * risk completing after termination.
 */
interface ILogTelemetryCrash {
    /** Encodes and durably persists [record]. */
    @Throws(Exception::class)
    fun emit(record: LogRecord)

    /** No-op today (writes in [emit] are already synchronous); kept for symmetry. */
    fun forceFlush()

    fun shutdown()
}
