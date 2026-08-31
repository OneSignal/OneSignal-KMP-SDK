package com.onesignal.logger.crash

/**
 * One entry in a platform's crash directory; [name] is the real on-disk name, since ownership and age
 * are read from it. Report an unreadable [lastModifiedMs] as `null`: any placeholder reads back as an age.
 */
data class CrashDirEntry(
    val name: String,
    val lastModifiedMs: Long?,
    val lengthBytes: Long,
)

/**
 * The bounds [CrashRetention] enforces; most callers use [CrashRetention.defaultPolicy]. [maxTotalBytes]
 * bounds *claim*, not bytes on disk: each record is charged at most [maxRecordBytes].
 */
data class CrashRetentionPolicy(
    val maxReadAgeMillis: Long = 72L * 60 * 60 * 1000,
    val maxRecordCount: Int = 50,
    val maxTotalBytes: Long = 2L * 1024 * 1024,
    val maxRecordBytes: Long = 512L * 1024,
    val ownedSuffix: String = ".otlp",
) {
    /**
     * Foreign, so never read. Datable only when the name also leads with `{millis}-`: a store must use this
     * suffix *and* that prefix for an interrupted write to stay reclaimable.
     */
    val ownedTempSuffix: String get() = "$ownedSuffix.tmp"
}

/**
 * Pure retention decisions for locally-buffered crash records, shared by every platform's log file store.
 * Never default a parameter here: an omission that still compiles reinstates the bug it was added to fix.
 */
object CrashRetention {
    val defaultPolicy: CrashRetentionPolicy = CrashRetentionPolicy()

    fun isOwned(name: String, policy: CrashRetentionPolicy): Boolean = name.endsWith(policy.ownedSuffix)

    /**
     * The single source of age: filesystem time, else leading millis in the name, else `null`. Never gate on
     * [CrashDirEntry.lastModifiedMs]; use the selectors' own [policy]. `null` never expires but is still capped.
     */
    fun effectiveWriteTimeMs(
        entry: CrashDirEntry,
        policy: CrashRetentionPolicy,
    ): Long? = entry.lastModifiedMs?.takeIf { it > 0 } ?: nameMillis(entry.name, policy)

    /**
     * Foreign entries old enough to reclaim; owned ones never (see [selectExpiredOwned]). An undatable entry
     * is never selected: it cannot clear the age gate that protects another writer's in-flight file.
     */
    fun selectUnrecognized(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        minAgeMillis: Long,
        policy: CrashRetentionPolicy,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            val writtenMs = effectiveWriteTimeMs(entry, policy)
            !isOwned(entry.name, policy) &&
                writtenMs != null &&
                nowMs - writtenMs >= minAgeMillis
        }

    /**
     * Owned entries past [CrashRetentionPolicy.maxReadAgeMillis], or dated so far ahead they never become
     * readable. An undatable entry is left to [selectOverflowOwned]: a failed read is not evidence of age.
     */
    fun selectExpiredOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        policy: CrashRetentionPolicy,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            isOwned(entry.name, policy) &&
                (isPastReadAge(entry, nowMs, policy) || isUnrecoverablyFutureDated(entry, nowMs, policy))
        }

    /**
     * Owned entries to evict so the directory fits both bounds, oldest-first; one too big for the remaining
     * budget is skipped, never a cutoff. [keepNames] are in-flight: never evicted, exempt from bytes, still counted.
     */
    fun selectOverflowOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        keepNames: Set<String>,
        policy: CrashRetentionPolicy,
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
     * Cheap exit: true guarantees [selectOverflowOwned] returns empty, so the caller skips sorting. Pass the
     * same [keepNames] and [policy], or the two stop measuring the same directory. Undatable entries count.
     */
    fun isWithinCaps(
        entries: List<CrashDirEntry>,
        keepNames: Set<String>,
        policy: CrashRetentionPolicy,
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
        policy: CrashRetentionPolicy,
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
     * Dated too far ahead to be clock skew: such a record never satisfies a `nowMs - writeTime` gate, so it
     * would hold a count slot for life while unreadable. Modest skew stays under the threshold.
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
     * Write time from the name: leading millis for this policy's own schemes, complete or interrupted; the
     * whole name for a legacy bare-millis foreign record. `3-tmp.dat` is in-flight, not a 1970 record.
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
