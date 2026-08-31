package com.onesignal.logger.crash

/**
 * One entry in a platform's crash directory.
 *
 * @property name the real on-disk name, not a display label — ownership is decided from its
 *   suffix, and for records this SDK wrote it also carries the write time.
 * @property lastModifiedMs filesystem write time in epoch millis, or `null` when the platform
 *   could not read it. Never substitute a placeholder: any number is read back as an age. Age
 *   is derived through [CrashRetention.effectiveWriteTimeMs], never from this field directly.
 * @property lengthBytes size on disk.
 */
data class CrashDirEntry(
    val name: String,
    val lastModifiedMs: Long?,
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
     * The single source of age for every decision here: the filesystem's write time when it is
     * readable, otherwise the millis the record's own name was built from, otherwise `null`.
     *
     * Both platforms name records `{millis}-{uuid}{ownedSuffix}` from the same clock reading
     * that stamps the file, so the name recovers the write time when attributes cannot be read
     * — under Apple data protection before first unlock, or a transient I/O error. The
     * filesystem wins when both are available: it is the one of the two that follows a rewrite,
     * and deferring to it leaves the readable-attributes path deciding exactly as before.
     *
     * A non-positive filesystem time counts as unreadable. Nothing this SDK writes predates the
     * epoch, and a platform reporting failure as `0` must not have that read back as maximum
     * age.
     *
     * The name is forgeable only by code that can already write to this directory, and which
     * could therefore plant or delete whole records anyway, so trusting it for age grants no
     * new capability. [selectOverflowOwned] and [isUnrecoverablyFutureDated] bound what an
     * absurd value can do.
     *
     * `null` means undatable — an unparseable name whose attributes are also unreadable. Such an
     * entry is never age-expired, but still counts toward [isWithinCaps] and is still evictable
     * by [selectOverflowOwned], so it cannot leak.
     *
     * Platforms must gate reads on this value rather than on [CrashDirEntry.lastModifiedMs], or
     * a record can be withheld from readers by one clock and reclaimed by another.
     */
    fun effectiveWriteTimeMs(entry: CrashDirEntry): Long? =
        entry.lastModifiedMs?.takeIf { it > 0 } ?: leadingMillis(entry.name)

    /**
     * Foreign entries old enough to reclaim. Owned records are never selected regardless of
     * age — [selectExpiredOwned] handles those.
     *
     * The age gate keeps another writer's in-flight file from being deleted mid-write, so an
     * undatable entry is never selected: it cannot clear a gate it cannot be measured against.
     * Legacy bare-millis names parse as their own write time, so a pre-upgrade file is still
     * reclaimable here while its attributes are unreadable — ownership is decided by suffix
     * alone, so parsing a time out of a foreign name cannot promote it to an owned record.
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
     * Owned entries no longer worth uploading: past [CrashRetentionPolicy.maxReadAgeMillis],
     * or dated so far ahead that they can never become readable (see
     * [isUnrecoverablyFutureDated]).
     *
     * These are reclaimed rather than hidden from readers, since hiding alone would let them
     * accumulate forever.
     *
     * Age comes from [effectiveWriteTimeMs], so an unreadable filesystem timestamp neither
     * expires a live record nor exempts a stale one. An undatable entry is never selected: a
     * failed read is not evidence of age. Boundedness for those comes from
     * [selectOverflowOwned], which still ranks them.
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
     * Size alone never causes eviction — deleting an oversized record destroys a captured
     * crash without ever attempting to send it. Size only caps *budget claim*, so one outsized
     * payload cannot displace the backlog. A record that does not fit the remaining budget is
     * skipped, not treated as a cutoff, so older records still get their chance.
     *
     * [keepNames] are the records callers are currently writing, retained regardless of sort
     * position: a backwards clock step could otherwise make a just-written record look oldest
     * and have the write delete it. A set rather than one name because concurrent crashing
     * threads each have a record in flight, and evicting a file another thread still holds
     * open destroys the crash being captured right now. They are kept even when they alone
     * exceed the caps; the overshoot ends when those writes finish and the names leave the set.
     *
     * [nowMs] caps how new a record may sort, so a future write time cannot hold a keep slot
     * against the whole backlog. Ordering cannot assume an expiry pass has run, because the
     * write path enforces caps on its own.
     */
    fun selectOverflowOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        keepNames: Set<String> = emptySet(),
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> {
        // Ties break on the millis embedded in the name, preserving write order when the
        // filesystem reports a coarser timestamp. Unparseable names sort last in their group.
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
     * True when the directory already fits both accumulation bounds, letting a caller on a
     * crash path skip sorting. Uses the same capped claim as [selectOverflowOwned] so the two
     * cannot disagree about whether work is needed.
     *
     * Every owned entry counts, undatable ones included: an entry excluded from the caps is
     * outside every bound at once, and would leak for the life of the install.
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
     * Platform stores gate reads on `nowMs - writeTime >= minAgeMillis`, which a future
     * timestamp never satisfies, so such a record is unreadable for its whole life while still
     * consuming a count slot. Using the retention window as the threshold preserves the
     * backwards-clock case: a record dated modestly ahead waits until the clock agrees it is
     * old, and only one still in the future after a full window is written off. This applies
     * equally to a time taken from the name, since the read gate uses the same value.
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
     * Eviction rank for [selectOverflowOwned]; a lower tier is evicted sooner. Write times only
     * order records *within* a tier, since comparing one against an undatable or unusable entry
     * decides nothing.
     *
     * Tier 0 can never pass a platform read gate, so it is worthless. Tier 1 may well be a live
     * crash report, but an entry we cannot date has no claim on a slot against one we can; under
     * real cap pressure it yields first.
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
