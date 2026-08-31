package com.onesignal.logger

/**
 * A stored crash record on disk.
 *
 * @property id opaque platform-chosen identifier, as accepted by [ILogFileStore.delete].
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
 * Platform-agnostic durable store for crash records, one encoded OTLP/protobuf payload each.
 * Implementations must be safe to call from a crash path: cheap, no heavy initialization.
 *
 * It is a bounded cache, not a queue, so an implementation must:
 *
 * - hold one [com.onesignal.logger.crash.CrashRetentionPolicy] and pass it everywhere, so bounds cannot drift;
 * - refuse writes over [com.onesignal.logger.crash.CrashRetentionPolicy.maxRecordBytes], and name interrupted
 *   writes with [com.onesignal.logger.crash.CrashRetentionPolicy.ownedTempSuffix] so they stay reclaimable;
 * - run *both* [com.onesignal.logger.crash.CrashRetention.selectExpiredOwned] and
 *   [com.onesignal.logger.crash.CrashRetention.selectOverflowOwned] on every path that scans the directory,
 *   and keep what they reclaim out of [listReadable] in the same pass. Overflow alone leaves an expired
 *   record that fits under the caps on disk forever; expiry alone leaves an unbounded backlog.
 *
 * Report `null` on [com.onesignal.logger.crash.CrashDirEntry.lastModifiedMs] when a modification time cannot be
 * read, never a substitute: any number reads back as an age, and `0` reads as maximum age, deleting live records.
 * Take every age, including the `minAgeMillis` gates in [listReadable] and [deleteUnrecognizedEntries], from
 * [com.onesignal.logger.crash.CrashRetention.effectiveWriteTimeMs], or one clock withholds a record another
 * reclaims. An entry it cannot date is still listed and still counts toward the caps, but no age gate selects it.
 */
interface ILogFileStore {
    /**
     * Persists [bytes] under a newly generated entry; `false` means a swallowed I/O failure.
     *
     * Deliberately neither `suspend` nor `@Throws`: this runs on the crashing thread inside the
     * uncaught-exception handler, so it must complete synchronously and never offload, and Kotlin/Native
     * exports a throwing Boolean-returning requirement that Swift cannot implement.
     */
    fun save(bytes: ByteArray): Boolean

    /**
     * Readable entries whose age is at least [minAgeMillis], so a file the crashing process may still be
     * writing is never read. Apply retention here before materializing payloads, or an over-cap backlog is
     * loaded and re-sent rather than reclaimed. Suspends so the scan and reads stay off the caller's thread
     * and to bridge to Swift `async`.
     */
    @Throws(Exception::class)
    suspend fun listReadable(minAgeMillis: Long): List<StoredLogFile>

    /** Deletes the entry with the given [id]. Safe to call if already gone. */
    @Throws(Exception::class)
    suspend fun delete(id: String)

    /**
     * Deletes entries this store does not own (e.g. legacy OpenTelemetry bare-millis files in a shared crash
     * directory) whose age is at least [minAgeMillis]; anything younger may be another writer's in-flight
     * file and must survive. Owned records are preserved *as a class* except those retention has
     * disqualified, and this is the only scan that runs when remote logging is disabled, so it is the sole
     * chance to bound a directory that is never otherwise read.
     *
     * @return number of unrecognized entries deleted
     */
    @Throws(Exception::class)
    suspend fun deleteUnrecognizedEntries(minAgeMillis: Long): Int = 0
}
