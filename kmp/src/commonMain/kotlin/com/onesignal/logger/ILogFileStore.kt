package com.onesignal.logger

import com.onesignal.logger.crash.CrashRetention

/** A stored crash record; [id] is the opaque platform-chosen identifier [ILogFileStore.delete] accepts. */
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
 * Durable store for encoded crash records, callable from a crash path: cheap, no heavy init. A bounded
 * cache, not a queue: every scanning path runs both [CrashRetention] reclaim passes under one policy.
 */
interface ILogFileStore {
    /**
     * Persists [bytes]; `false` is a swallowed I/O failure. Deliberately neither `suspend` nor `@Throws`: it
     * runs on the crashing thread so must not offload, and Swift cannot implement a throwing Boolean export.
     */
    fun save(bytes: ByteArray): Boolean

    /**
     * Entries at least [minAgeMillis] old, so a file still being written is never read. Apply retention before
     * materializing payloads, or an over-cap backlog is loaded and re-sent rather than reclaimed.
     */
    @Throws(Exception::class)
    suspend fun listReadable(minAgeMillis: Long): List<StoredLogFile>

    /** Deletes the entry with the given [id]. Safe to call if already gone. */
    @Throws(Exception::class)
    suspend fun delete(id: String)

    /**
     * Deletes foreign entries at least [minAgeMillis] old; anything younger may be another writer's in-flight
     * file. The only scan that runs with remote logging off, so it must apply the owned bounds too.
     */
    @Throws(Exception::class)
    suspend fun deleteUnrecognizedEntries(minAgeMillis: Long): Int = 0
}
