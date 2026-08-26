package com.onesignal.logger.crash

/**
 * One entry in a platform's crash directory.
 *
 * @property name the real on-disk name, not a display label — ownership is decided from its
 *   suffix.
 * @property lastModifiedMs write time in epoch millis.
 * @property lengthBytes size on disk.
 */
data class CrashDirEntry(
    val name: String,
    val lastModifiedMs: Long,
    val lengthBytes: Long,
)

/**
 * The bounds [CrashRetention] enforces. Platforms needing different numbers construct their
 * own; everyone else uses [CrashRetention.defaultPolicy].
 *
 * @property maxReadAgeMillis how long an owned record stays eligible for upload.
 * @property maxRecordCount record-count ceiling.
 * @property maxTotalBytes byte budget across all owned records. Bounds *claim*, not bytes on
 *   disk: each record is charged at most [maxRecordBytes], so oversized records inherited
 *   from a build without a write-time limit can occupy more than this while still counting as
 *   within cap.
 * @property maxRecordBytes largest payload a store should write.
 * @property ownedSuffix suffix marking a record this SDK wrote. Anything else is foreign.
 */
data class CrashRetentionPolicy(
    val maxReadAgeMillis: Long = 72L * 60 * 60 * 1000,
    val maxRecordCount: Int = 50,
    val maxTotalBytes: Long = 2L * 1024 * 1024,
    val maxRecordBytes: Long = 512L * 1024,
    val ownedSuffix: String = ".otlp",
)

/**
 * Retention policy for locally-buffered crash records, shared by every platform implementing
 * [com.onesignal.logger.ILogFileStore].
 *
 * The store is a cache, not a queue: without bounds, a record that never uploads is re-read
 * and re-sent on every launch for the life of the install.
 *
 * Pure by design — no file, clock or platform types. Callers pass the directory listing and
 * the current time, then apply the returned decisions with their own I/O.
 */
object CrashRetention {
    val defaultPolicy: CrashRetentionPolicy = CrashRetentionPolicy()

    fun isOwned(name: String, policy: CrashRetentionPolicy = defaultPolicy): Boolean = name.endsWith(policy.ownedSuffix)

    /**
     * Foreign entries old enough to reclaim. Owned records are never selected regardless of
     * age — [selectExpiredOwned] handles those.
     *
     * The age gate keeps another writer's in-flight file from being deleted mid-write.
     */
    fun selectUnrecognized(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        minAgeMillis: Long,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            !isOwned(entry.name, policy) && nowMs - entry.lastModifiedMs >= minAgeMillis
        }

    /**
     * Owned entries no longer worth uploading: past [CrashRetentionPolicy.maxReadAgeMillis],
     * or dated so far ahead that they can never become readable (see
     * [isUnrecoverablyFutureDated]).
     *
     * These are reclaimed rather than hidden from readers, since hiding alone would let them
     * accumulate forever.
     */
    fun selectExpiredOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            isOwned(entry.name, policy) &&
                (
                    nowMs - entry.lastModifiedMs > policy.maxReadAgeMillis ||
                        isUnrecoverablyFutureDated(entry, nowMs, policy)
                    )
        }

    /**
     * Owned entries to evict so the directory fits the count and byte bounds. Newest are kept;
     * the excess is returned oldest-first.
     *
     * Size alone never causes eviction — deleting an oversized record destroys a captured
     * crash without ever attempting to send it. Size only caps *budget claim*, so one outsized
     * payload cannot displace the backlog. A record that does not fit the remaining budget is
     * skipped, not treated as a cutoff, so older records still get their chance.
     *
     * [keepName] is the record the caller just wrote, retained regardless of sort position: a
     * backwards clock step could otherwise make it look oldest and have the write delete it.
     *
     * [nowMs] caps how new a record may sort, so a future write time cannot hold a keep slot
     * against the whole backlog. Ordering cannot assume an expiry pass has run, because the
     * write path enforces caps on its own.
     */
    fun selectOverflowOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        keepName: String? = null,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> {
        fun sortKey(entry: CrashDirEntry): Long =
            if (isUnrecoverablyFutureDated(entry, nowMs, policy)) {
                Long.MIN_VALUE
            } else {
                minOf(entry.lastModifiedMs, nowMs)
            }

        // Ties break on the millis embedded in the name, preserving write order when the
        // filesystem reports a coarser timestamp. Unparseable names sort last in their group.
        val newestFirst =
            entries
                .filter { isOwned(it.name, policy) }
                .sortedWith(
                    compareByDescending<CrashDirEntry> { sortKey(it) }
                        .thenByDescending { leadingMillis(it.name)?.coerceAtMost(nowMs) ?: Long.MIN_VALUE },
                )

        fun budgetClaim(entry: CrashDirEntry): Long = minOf(entry.lengthBytes, policy.maxRecordBytes)

        val kept = HashSet<String>()
        var keptBytes = 0L
        keepName?.let { name ->
            newestFirst.firstOrNull { it.name == name }?.let {
                kept.add(it.name)
                keptBytes += budgetClaim(it)
            }
        }
        for (entry in newestFirst) {
            if (kept.contains(entry.name)) continue
            if (kept.size >= policy.maxRecordCount) break
            if (keptBytes + budgetClaim(entry) > policy.maxTotalBytes) continue
            kept.add(entry.name)
            keptBytes += budgetClaim(entry)
        }
        return newestFirst.filterNot { kept.contains(it.name) }.reversed()
    }

    /**
     * True when the directory already fits both accumulation bounds, letting a caller on a
     * crash path skip sorting. Uses the same capped claim as [selectOverflowOwned] so the two
     * cannot disagree about whether work is needed.
     */
    fun isWithinCaps(
        entries: List<CrashDirEntry>,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): Boolean {
        val owned = entries.filter { isOwned(it.name, policy) }
        val claimed = owned.sumOf { minOf(it.lengthBytes, policy.maxRecordBytes) }
        return owned.size <= policy.maxRecordCount && claimed <= policy.maxTotalBytes
    }

    /**
     * Inventory line for rollout verification, with per-file detail capped at [maxSample].
     * A negative [maxSample] is treated as zero — this runs on a crash-adjacent path, where a
     * logging helper must not be the thing that fails.
     */
    fun formatInventory(
        label: String,
        path: String,
        entries: List<CrashDirEntry>,
        nowMs: Long,
        maxSample: Int,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): String {
        if (entries.isEmpty()) {
            return "OneSignal: Crash storage inventory [$label] ($path): empty"
        }
        val sampleSize = maxSample.coerceAtLeast(0)
        val otlp = entries.count { isOwned(it.name, policy) }
        val legacy = entries.size - otlp
        val summary =
            entries.take(sampleSize).joinToString(separator = "; ") { entry ->
                "name=${entry.name} bytes=${entry.lengthBytes} ageMs=${nowMs - entry.lastModifiedMs}"
            }
        val truncated =
            if (entries.size > sampleSize) " …(+${entries.size - sampleSize} more)" else ""
        return "OneSignal: Crash storage inventory [$label] ($path): " +
            "total=${entries.size} otlp=$otlp legacy=$legacy [$summary]$truncated"
    }

    /**
     * True when [entry] is dated too far ahead to be a clock artifact.
     *
     * Platform stores gate reads on `nowMs - lastModifiedMs >= minAgeMillis`, which a future
     * timestamp never satisfies, so such a record is unreadable for its whole life while still
     * consuming a count slot. Using the retention window as the threshold preserves the
     * backwards-clock case: a record dated modestly ahead waits until the clock agrees it is
     * old, and only one still in the future after a full window is written off.
     */
    private fun isUnrecoverablyFutureDated(
        entry: CrashDirEntry,
        nowMs: Long,
        policy: CrashRetentionPolicy,
    ): Boolean = entry.lastModifiedMs - nowMs > policy.maxReadAgeMillis

    /** Leading millis of a `{millis}-{uuid}.otlp` name, or null for anything else. */
    private fun leadingMillis(name: String): Long? = name.substringBefore('-').toLongOrNull()
}
