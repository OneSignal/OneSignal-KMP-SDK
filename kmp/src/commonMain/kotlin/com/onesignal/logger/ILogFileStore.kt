package com.onesignal.logger

/**
 * A stored crash record on disk.
 *
 * @property id opaque identifier (the platform decides what this is — e.g. a file
 *   name). Used to [ILogFileStore.read] and [ILogFileStore.delete] the entry.
 * @property bytes the encoded payload that was written.
 */
data class StoredLogFile(
    val id: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredLogFile) return false
        return id == other.id && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
}

/**
 * Platform-agnostic durable store for crash records.
 *
 * Replaces OpenTelemetry's `disk-buffering` contrib library. The on-disk format is
 * entirely owned by this module (each record is one encoded OTLP/protobuf payload), so
 * the same logic works on any platform that can provide simple file primitives.
 *
 * Implementations must be safe to call from a crash path (i.e. cheap, no heavy
 * initialization).
 *
 * ## Retention is required, not optional
 *
 * This store is a bounded cache, not an unbounded queue. `disk-buffering` applied an age
 * ceiling and size limits; replacing it means implementations now owe the same guarantees, or
 * a record that never uploads successfully is re-read and re-sent on every launch for the life
 * of the install while the directory grows without bound.
 *
 * [com.onesignal.logger.crash.CrashRetention] supplies that policy as pure functions so every
 * platform behaves identically. An implementation is expected to:
 *
 * - refuse writes larger than [com.onesignal.logger.crash.CrashRetention.MAX_RECORD_BYTES] in
 *   [save], so no single payload can claim the whole budget;
 * - reclaim entries chosen by
 *   [com.onesignal.logger.crash.CrashRetention.selectExpiredOwned] and
 *   [com.onesignal.logger.crash.CrashRetention.selectOverflowOwned] on every path that scans
 *   the directory — not only after a write, or a backlog inherited from an earlier build is
 *   never trimmed;
 * - keep reclaimed entries out of [listReadable] in the same pass.
 */
interface ILogFileStore {
    /**
     * Persists [bytes] under a newly generated entry.
     *
     * Intentionally NOT a suspend function: the only caller is the crash sink, which
     * runs on the crashing thread inside the uncaught-exception handler and must
     * complete the write synchronously before the process dies. Implementations must
     * keep it cheap and never offload to another thread/queue on this path.
     *
     * @return `true` when the bytes were durably written; `false` on swallowed I/O
     *   failure so callers can avoid logging a false "saved" success.
     *
     * This method intentionally has no [Throws] annotation. Kotlin/Native imports a
     * throwing Boolean-returning Objective-C requirement that Swift cannot implement;
     * persistence failures are represented by the return value instead.
     */
    fun save(bytes: ByteArray): Boolean

    /**
     * Returns all readable entries whose age is at least [minAgeMillis]. The age
     * gate mirrors `minFileAgeForReadMillis` from the old pipeline: it guarantees
     * we never read a file that may still be mid-write from the crashing process.
     *
     * Implementations should apply retention here before materializing payloads, so an
     * over-cap or expired backlog is reclaimed rather than loaded and re-sent.
     *
     * Suspends so implementations can perform the (blocking) directory scan and reads
     * on a background dispatcher — keeping the shared upload pipeline off the caller's
     * thread on every platform, and bridging to Swift `async` on iOS.
     */
    @Throws(Exception::class)
    suspend fun listReadable(minAgeMillis: Long): List<StoredLogFile>

    /** Deletes the entry with the given [id]. Safe to call if already gone. */
    @Throws(Exception::class)
    suspend fun delete(id: String)

    /**
     * Deletes on-disk entries this store does not own (e.g. legacy OpenTelemetry
     * bare-millis files left in a shared crash directory) whose age is at least
     * [minAgeMillis].
     *
     * Foreign/unrecognized files younger than [minAgeMillis] must be preserved so an
     * in-flight write by another writer is not deleted mid-write. Callers typically pass
     * [ILoggerPlatformProvider.minFileAgeForReadMillis] for the same safety margin used by
     * [listReadable].
     *
     * Owned records are preserved here *as a class* — a failed upload or a record still under
     * the age gate must survive to be retried. The exceptions are the ones retention has
     * disqualified: entries past the age ceiling or beyond the accumulation caps are no longer
     * uploadable or no longer affordable, and reclaiming them is this method's other job. This
     * is also the only scan that runs when remote logging is disabled, so it is the sole
     * opportunity to bound a directory that is never otherwise read.
     *
     * Default is a no-op for test doubles / platforms with no shared-directory legacy.
     *
     * @param minAgeMillis minimum age before a foreign file may be deleted
     * @return number of unrecognized entries deleted
     */
    @Throws(Exception::class)
    suspend fun deleteUnrecognizedEntries(minAgeMillis: Long): Int = 0
}
