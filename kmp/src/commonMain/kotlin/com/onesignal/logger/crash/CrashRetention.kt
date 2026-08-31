package com.onesignal.logger.crash

/**
 * One entry in a platform's crash directory.
 *
 * @property name the real on-disk name, not a display label: ownership and write time are read
 *   from it.
 * @property lastModifiedMs filesystem write time in epoch millis, or `null` if unreadable. Never
 *   substitute a placeholder; any number reads back as an age. Derive age through
 *   [CrashRetention.effectiveWriteTimeMs], never from this field.
 * @property lengthBytes size on disk.
 */
data class CrashDirEntry(
    val name: String,
    val lastModifiedMs: Long?,
    val lengthBytes: Long,
)

/**
 * The bounds [CrashRetention] enforces; most callers use [CrashRetention.defaultPolicy].
 *
 * @property maxReadAgeMillis how long an owned record stays eligible for upload.
 * @property maxRecordCount record-count ceiling.
 * @property maxTotalBytes byte budget across owned records. Bounds *claim*, not bytes on disk:
 *   each record is charged at most [maxRecordBytes], so oversized records can occupy more while
 *   still counting as within cap.
 * @property maxRecordBytes largest payload a store should write.
 * @property ownedSuffix suffix marking a record this SDK wrote; anything else is foreign.
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
 * Pure by design: callers pass the directory listing and the current time, then apply the
 * returned decisions with their own I/O.
 */
object CrashRetention {
    val defaultPolicy: CrashRetentionPolicy = CrashRetentionPolicy()

    fun isOwned(name: String, policy: CrashRetentionPolicy = defaultPolicy): Boolean = name.endsWith(policy.ownedSuffix)

    /**
     * The single source of age for every decision here: the filesystem write time when readable,
     * else the millis embedded in the record's own name, else `null`.
     *
     * Both platforms build names as `{millis}-{uuid}{ownedSuffix}` from the clock reading that
     * stamps the file, so the name recovers the write time when attributes cannot be read. It is
     * forgeable only by code that could already plant or delete records outright. A non-positive
     * filesystem time counts as unreadable: a platform reporting failure as `0` must not have
     * that read back as maximum age.
     *
     * `null` means undatable. Such an entry is never age-expired, but still counts toward
     * [isWithinCaps] and is still evictable by [selectOverflowOwned], so it cannot leak.
     *
     * Platforms must gate reads on this value rather than on [CrashDirEntry.lastModifiedMs], or
     * a record can be withheld by one clock and reclaimed by another.
     */
    fun effectiveWriteTimeMs(entry: CrashDirEntry): Long? =
        entry.lastModifiedMs?.takeIf { it > 0 } ?: leadingMillis(entry.name)

    /**
     * Foreign entries old enough to reclaim; owned records are never selected regardless of age
     * (see [selectExpiredOwned]).
     *
     * The age gate keeps another writer's in-flight file from being deleted mid-write, so an
     * undatable entry is never selected: it cannot clear a gate it cannot be measured against.
     */
    fun selectUnrecognized(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        minAgeMillis: Long,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            val writtenMs = effectiveWriteTimeMs(entry)
            !isOwned(entry.name, policy) &&
                writtenMs != null &&
                nowMs - writtenMs >= minAgeMillis
        }

    /**
     * Owned entries no longer worth uploading: past [CrashRetentionPolicy.maxReadAgeMillis], or
     * dated so far ahead they can never become readable (see [isUnrecoverablyFutureDated]).
     *
     * An undatable entry is never selected: a failed read is not evidence of age. Bounding those
     * falls to [selectOverflowOwned].
     */
    fun selectExpiredOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            if (!isOwned(entry.name, policy)) return@filter false
            val writtenMs = effectiveWriteTimeMs(entry) ?: return@filter false
            nowMs - writtenMs > policy.maxReadAgeMillis ||
                isUnrecoverablyFutureDated(entry, nowMs, policy)
        }

    /**
     * Owned entries to evict so the directory fits the count and byte bounds. Newest are kept;
     * the excess is returned oldest-first.
     *
     * Size alone never evicts: it caps *budget claim* only, and a record that does not fit the
     * remaining budget is skipped rather than treated as a cutoff, so one outsized payload
     * cannot displace the backlog.
     *
     * [keepNames] are records callers are currently writing and are never evicted, even when
     * they alone exceed the caps: a backwards clock step or a second crashing thread would
     * otherwise let one write destroy a record still being captured. The overshoot ends when
     * those writes finish. [nowMs] caps how new a record may sort, so a future write time cannot
     * hold a keep slot against the whole backlog.
     */
    fun selectOverflowOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        keepNames: Set<String> = emptySet(),
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> {
        // Ties break on the millis embedded in the name, preserving write order when the
        // filesystem reports a coarser timestamp.
        val newestFirst =
            entries
                .filter { isOwned(it.name, policy) }
                .sortedWith(
                    compareByDescending<CrashDirEntry> { evictionTier(it, nowMs, policy) }
                        .thenByDescending { effectiveWriteTimeMs(it)?.coerceAtMost(nowMs) ?: Long.MIN_VALUE }
                        .thenByDescending { leadingMillis(it.name)?.coerceAtMost(nowMs) ?: Long.MIN_VALUE },
                )

        fun budgetClaim(entry: CrashDirEntry): Long = minOf(entry.lengthBytes, policy.maxRecordBytes)

        val kept = HashSet<String>()
        var keptBytes = 0L
        for (entry in newestFirst) {
            if (entry.name in keepNames && kept.add(entry.name)) {
                keptBytes += budgetClaim(entry)
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
     * True when the directory already fits both bounds, letting a caller on the crash path skip
     * sorting. Uses the same capped claim as [selectOverflowOwned] so the two cannot disagree.
     *
     * Every owned entry counts, undatable ones included: an entry excluded from the caps is
     * outside every bound at once and leaks for the life of the install.
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
     * Inventory line for rollout verification, with per-file detail capped at [maxSample]. A
     * negative [maxSample] is treated as zero: this runs crash-adjacent, where a logging helper
     * must not be the thing that fails.
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
                val age = effectiveWriteTimeMs(entry)?.let { nowMs - it } ?: "unknown"
                "name=${entry.name} bytes=${entry.lengthBytes} ageMs=$age"
            }
        val truncated =
            if (entries.size > sampleSize) " …(+${entries.size - sampleSize} more)" else ""
        return "OneSignal: Crash storage inventory [$label] ($path): " +
            "total=${entries.size} otlp=$otlp legacy=$legacy [$summary]$truncated"
    }

    /**
     * True when [entry]'s [effectiveWriteTimeMs] is too far ahead to be a clock artifact.
     *
     * Read gates use `nowMs - writeTime >= minAgeMillis`, which a future timestamp never
     * satisfies, so such a record is unreadable for its whole life while still holding a count
     * slot. The retention window is the threshold so a record dated modestly ahead waits for the
     * clock to agree rather than being written off.
     */
    private fun isUnrecoverablyFutureDated(
        entry: CrashDirEntry,
        nowMs: Long,
        policy: CrashRetentionPolicy,
    ): Boolean {
        val writtenMs = effectiveWriteTimeMs(entry) ?: return false
        return writtenMs - nowMs > policy.maxReadAgeMillis
    }

    /**
     * Eviction rank for [selectOverflowOwned]; a lower tier is evicted sooner. Ordering cannot
     * assume an expiry pass has run, since the write path enforces caps on its own.
     *
     * Tier 0 can never pass a platform read gate. Tier 1 may be a live crash report, but an
     * entry we cannot date yields to one we can under cap pressure.
     */
    private fun evictionTier(
        entry: CrashDirEntry,
        nowMs: Long,
        policy: CrashRetentionPolicy,
    ): Int =
        when {
            isUnrecoverablyFutureDated(entry, nowMs, policy) -> 0
            effectiveWriteTimeMs(entry) == null -> 1
            else -> 2
        }

    /**
     * Leading millis of a `{millis}-{uuid}{ownedSuffix}` name, or null for anything else. Also
     * parses a legacy bare-millis name, which is harmless: ownership is decided by suffix.
     */
    private fun leadingMillis(name: String): Long? = name.substringBefore('-').toLongOrNull()
}
