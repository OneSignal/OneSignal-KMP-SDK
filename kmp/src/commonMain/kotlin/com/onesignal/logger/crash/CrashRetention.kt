package com.onesignal.logger.crash

/**
 * One entry in a platform's crash directory.
 *
 * @property name the real on-disk name, not a display label: ownership and write time are read from it.
 * @property lastModifiedMs filesystem write time in epoch millis, or `null` if unreadable. Never substitute a
 *   placeholder; any number reads back as an age. Take age from [CrashRetention.effectiveWriteTimeMs] instead.
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
 * @property maxTotalBytes byte budget across owned records. Bounds *claim*, not bytes on disk: each record is
 *   charged at most [maxRecordBytes], so oversized records can occupy more while still counting as within cap.
 * @property maxRecordBytes largest payload a store should write.
 * @property ownedSuffix suffix marking a record this SDK wrote; anything else is foreign.
 */
data class CrashRetentionPolicy(
    val maxReadAgeMillis: Long = 72L * 60 * 60 * 1000,
    val maxRecordCount: Int = 50,
    val maxTotalBytes: Long = 2L * 1024 * 1024,
    val maxRecordBytes: Long = 512L * 1024,
    val ownedSuffix: String = ".otlp",
) {
    /** Foreign, so it is never read, but still datable. Stores must name temp files with this or they leak. */
    val ownedTempSuffix: String get() = "$ownedSuffix.tmp"
}

/**
 * Retention policy for locally-buffered crash records, shared by every platform implementing
 * [com.onesignal.logger.ILogFileStore]. Pure: callers pass the directory listing and the current
 * time, then apply the returned decisions with their own I/O.
 */
object CrashRetention {
    val defaultPolicy: CrashRetentionPolicy = CrashRetentionPolicy()

    fun isOwned(name: String, policy: CrashRetentionPolicy = defaultPolicy): Boolean = name.endsWith(policy.ownedSuffix)

    /**
     * The single source of age for every decision here: the filesystem write time, else the millis
     * in the record's own name, else `null`. A non-positive reading from *either* source counts as
     * unreadable.
     *
     * `null` means undatable: never age-expired, but still counted by [isWithinCaps] and still
     * evictable by [selectOverflowOwned]. Platforms must gate reads on this rather than on
     * [CrashDirEntry.lastModifiedMs], under the same [policy] their selectors use, or one clock
     * withholds a record another reclaims.
     */
    fun effectiveWriteTimeMs(
        entry: CrashDirEntry,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): Long? = entry.lastModifiedMs?.takeIf { it > 0 } ?: nameMillis(entry.name, policy)

    /**
     * Foreign entries old enough to reclaim; owned records are never selected (see [selectExpiredOwned]).
     * The age gate keeps another writer's in-flight file from being deleted mid-write, so an undatable
     * entry is never selected: it cannot clear a gate it cannot be measured against.
     */
    fun selectUnrecognized(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        minAgeMillis: Long,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            val writtenMs = effectiveWriteTimeMs(entry, policy)
            !isOwned(entry.name, policy) &&
                writtenMs != null &&
                nowMs - writtenMs >= minAgeMillis
        }

    /**
     * Owned entries no longer worth uploading: past [CrashRetentionPolicy.maxReadAgeMillis], or dated so
     * far ahead they can never become readable. An undatable entry is never selected, since a failed read
     * is not evidence of age; bounding those falls to [selectOverflowOwned].
     */
    fun selectExpiredOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            isOwned(entry.name, policy) &&
                (isPastReadAge(entry, nowMs, policy) || isUnrecoverablyFutureDated(entry, nowMs, policy))
        }

    /**
     * Owned entries to evict so the directory fits the count and byte bounds, returned oldest-first.
     *
     * Size alone never evicts: a record that does not fit the remaining budget is skipped rather than
     * treated as a cutoff, so one outsized payload cannot displace the backlog.
     *
     * [keepNames] are records callers are currently writing and are never evicted. They claim no byte
     * budget, so what holds is [CrashRetentionPolicy.maxTotalBytes] across unprotected survivors *plus*
     * whatever [keepNames] hold; they do still occupy count slots.
     */
    fun selectOverflowOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        keepNames: Set<String> = emptySet(),
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> {
        // Ties break on the millis in the name: filesystem timestamps can be coarser than write order.
        val newestFirst =
            entries
                .filter { isOwned(it.name, policy) }
                .sortedWith(
                    compareByDescending<CrashDirEntry> { evictionTier(it, nowMs, policy) }
                        .thenByDescending { effectiveWriteTimeMs(it, policy)?.coerceAtMost(nowMs) ?: Long.MIN_VALUE }
                        .thenByDescending { nameMillis(it.name, policy)?.coerceAtMost(nowMs) ?: Long.MIN_VALUE },
                )

        fun budgetClaim(entry: CrashDirEntry): Long = minOf(entry.lengthBytes, policy.maxRecordBytes)

        val kept = HashSet<String>()
        var keptBytes = 0L
        for (entry in newestFirst) {
            if (entry.name in keepNames) kept.add(entry.name)
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
     * Cheap exit for the crash path: true guarantees [selectOverflowOwned] returns empty, so the caller can
     * skip sorting. Pass it the same [keepNames] and [policy] or the two stop measuring the same directory.
     *
     * Every owned entry counts toward the record cap, undatable ones included: an entry excluded from the
     * caps is outside every bound at once and leaks for the life of the install.
     */
    fun isWithinCaps(
        entries: List<CrashDirEntry>,
        keepNames: Set<String> = emptySet(),
        policy: CrashRetentionPolicy = defaultPolicy,
    ): Boolean {
        val owned = entries.filter { isOwned(it.name, policy) }
        val claimed =
            owned.filterNot { it.name in keepNames }
                .sumOf { minOf(it.lengthBytes, policy.maxRecordBytes) }
        return owned.size <= policy.maxRecordCount && claimed <= policy.maxTotalBytes
    }

    /**
     * Inventory line for rollout verification, with per-file detail capped at [maxSample]. A negative
     * [maxSample] is treated as zero: this runs crash-adjacent and must not be the thing that fails.
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
                val age = effectiveWriteTimeMs(entry, policy)?.let { nowMs - it } ?: "unknown"
                "name=${entry.name} bytes=${entry.lengthBytes} ageMs=$age"
            }
        val truncated =
            if (entries.size > sampleSize) " …(+${entries.size - sampleSize} more)" else ""
        return "OneSignal: Crash storage inventory [$label] ($path): " +
            "total=${entries.size} otlp=$otlp legacy=$legacy [$summary]$truncated"
    }

    /**
     * True when [entry] is dated too far ahead to be a clock artifact. Read gates use
     * `nowMs - writeTime >= minAgeMillis`, which such a record never satisfies, so it would hold a count
     * slot for life while being unreadable. Modest skew stays under the threshold and is tolerated.
     */
    private fun isUnrecoverablyFutureDated(
        entry: CrashDirEntry,
        nowMs: Long,
        policy: CrashRetentionPolicy,
    ): Boolean {
        val writtenMs = effectiveWriteTimeMs(entry, policy) ?: return false
        return writtenMs - nowMs > policy.maxReadAgeMillis
    }

    /** Shared with [selectExpiredOwned] so ranking and expiry cannot disagree on what is dead. */
    private fun isPastReadAge(
        entry: CrashDirEntry,
        nowMs: Long,
        policy: CrashRetentionPolicy,
    ): Boolean {
        val writtenMs = effectiveWriteTimeMs(entry, policy) ?: return false
        return nowMs - writtenMs > policy.maxReadAgeMillis
    }

    /**
     * Eviction rank for [selectOverflowOwned]; a lower tier is evicted sooner. The write path enforces caps
     * without running expiry, so ranking alone must keep overflow from shedding a live record before a dead one.
     */
    private fun evictionTier(
        entry: CrashDirEntry,
        nowMs: Long,
        policy: CrashRetentionPolicy,
    ): Int =
        when {
            isUnrecoverablyFutureDated(entry, nowMs, policy) -> 0
            isPastReadAge(entry, nowMs, policy) -> 1
            effectiveWriteTimeMs(entry, policy) == null -> 2
            else -> 3
        }

    /**
     * Write time carried by a record's own name: leading millis for this policy's own schemes, complete or
     * interrupted; the whole name for a legacy bare-millis foreign record. Any other foreign shape is not an
     * epoch reading, since `3-tmp.dat` is an in-flight file, not a 1970 one.
     */
    private fun nameMillis(
        name: String,
        policy: CrashRetentionPolicy,
    ): Long? =
        if (isOwned(name, policy) || name.endsWith(policy.ownedTempSuffix)) {
            name.substringBefore('-').toLongOrNull()?.takeIf { it > 0 }
        } else {
            name.toLongOrNull()?.takeIf { it > 0 }
        }
}
