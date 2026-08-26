package com.onesignal.logger.crash

/**
 * One entry in a platform's crash directory, reduced to the three facts retention decisions
 * need. Platforms build these from their own file APIs; everything downstream is shared.
 *
 * @property name platform-opaque identifier, typically the file name. Ownership is decided
 *   from its suffix, so it must be the real on-disk name and not a display label.
 * @property lastModifiedMs write time in epoch millis.
 * @property lengthBytes size on disk. Required rather than defaulted: budget claim is
 *   `min(lengthBytes, maxRecordBytes)`, so an omitted size would silently claim zero and
 *   disable the byte budget for that record.
 */
data class CrashDirEntry(
    val name: String,
    val lastModifiedMs: Long,
    val lengthBytes: Long,
)

/**
 * The bounds [CrashRetention] enforces, grouped so a caller states them once.
 *
 * Kotlin default arguments do not survive the Objective-C export, so a Swift caller must
 * otherwise spell out every bound at every call site. Four of them are adjacent, same-typed
 * numbers, which makes a copied-and-edited call site an easy place for values to drift apart
 * — passing [maxRecordBytes] where [maxTotalBytes] belongs lets a single record claim the
 * entire directory budget and evicts nearly the whole backlog. Passing one policy value
 * removes the opportunity: the bounds are named once, at construction, and every selector
 * reads the same instance.
 *
 * Platforms that need different numbers construct their own; everyone else uses
 * [CrashRetention.defaultPolicy].
 *
 * @property maxReadAgeMillis how long an owned record stays eligible for upload. Carried
 *   over from the disk-buffering configuration the OpenTelemetry pipeline used. Past this the
 *   payload is too stale to be worth shipping, and the ceiling is what stops a
 *   permanently-failing record from being retried forever.
 * @property maxRecordCount record-count ceiling. This is the bound that normally binds:
 *   crash records are single-event OTLP payloads of a few KB, so this covers far more unsent
 *   crashes than a healthy install will ever accumulate.
 * @property maxTotalBytes byte budget across all owned records, as a backstop for payload
 *   profiles the count cap alone would not contain. This bounds *claim*, not bytes on disk.
 *   Since writes are size-limited by [maxRecordBytes] the two coincide for anything written
 *   by a build enforcing that limit. They diverge only for records inherited from one that
 *   did not: each claims at most [maxRecordBytes], so a few oversized leftovers can occupy
 *   more than this while still counting as within cap. That is deliberate — they are real
 *   crashes and deserve an upload attempt — and it stays bounded by [maxRecordCount] and by
 *   [maxReadAgeMillis] aging them out.
 * @property maxRecordBytes largest payload a store should write. Refusing at the source is
 *   what makes "every stored record fits the shared budget" an invariant; without it an
 *   outsized payload is either kept at the cost of everything else, or written and then
 *   deleted before it can be sent.
 * @property ownedSuffix suffix marking a record this SDK wrote. Anything else in the
 *   directory is foreign.
 */
data class CrashRetentionPolicy(
    val maxReadAgeMillis: Long = 72L * 60 * 60 * 1000,
    val maxRecordCount: Int = 50,
    val maxTotalBytes: Long = 2L * 1024 * 1024,
    val maxRecordBytes: Long = 512L * 1024,
    val ownedSuffix: String = ".otlp",
)

/**
 * Retention policy for locally-buffered crash records, shared by every platform that
 * implements [com.onesignal.logger.ILogFileStore].
 *
 * The store is a cache, not a queue: a record that never uploads must not live forever. Three
 * bounds keep it finite — an age ceiling, a record count, and a byte budget — and a write-time
 * size limit keeps any single payload from dominating the budget. Without them a report that
 * repeatedly fails to upload is re-read and re-sent on every launch for the life of the
 * install, which is what the OpenTelemetry `disk-buffering` library used to prevent before it
 * was removed.
 *
 * Everything here is pure: no file, clock or platform types. Callers supply the directory
 * listing and the current time, apply the returned decisions with their own I/O, and get
 * identical behavior on every platform. That also makes it directly unit-testable in
 * `commonTest` with no filesystem.
 *
 * Sizing note: the numbers live in [CrashRetentionPolicy] and every selector takes one. A
 * platform whose payload profile or cache-eviction pressure differs can pass its own without
 * forking the algorithms; everyone else passes [defaultPolicy].
 */
object CrashRetention {
    /** The bounds every platform uses unless it has a reason not to. */
    val defaultPolicy: CrashRetentionPolicy = CrashRetentionPolicy()

    /** True when [name] is a record this SDK wrote. */
    fun isOwned(name: String, policy: CrashRetentionPolicy = defaultPolicy): Boolean = name.endsWith(policy.ownedSuffix)

    /**
     * Foreign entries old enough to reclaim — files sharing the directory that this store does
     * not own, such as leftovers from a previous logging implementation. Owned records are
     * never selected here regardless of age; [selectExpiredOwned] handles those.
     *
     * The age gate protects an in-flight write by another writer from being deleted mid-write.
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
     * Owned entries no longer worth uploading, reclaimed rather than merely hidden from
     * readers — hiding alone would let them accumulate indefinitely.
     *
     * Two ways a record qualifies. The ordinary one is age past
     * [CrashRetentionPolicy.maxReadAgeMillis]. The other is a write time so far in the future
     * that it can no longer be a clock artifact: platform stores gate reads on
     * `now - lastModifiedMs >= minAgeMillis`, which a future timestamp never satisfies, so
     * such a record is unreadable for its entire life while still consuming a count slot and
     * budget. Reclaiming it is the only way it ever leaves the directory.
     *
     * The threshold is the retention window itself, which keeps the deliberate
     * backwards-clock protection intact: a record dated modestly ahead of now — the clock
     * stepped back since it was written — is left alone to wait until the clock agrees it is
     * old. Only a record that would still be in the future after the entire window has
     * elapsed is treated as unrecoverable.
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
     * Owned entries to evict so the directory fits [CrashRetentionPolicy.maxRecordCount] and
     * [CrashRetentionPolicy.maxTotalBytes]. Newest are kept; the excess is returned
     * oldest-first so callers can delete in that order.
     *
     * Size is never on its own a reason to evict. A payload too large to upload should be
     * refused at write time; deleting one already on disk destroys a captured crash without
     * ever attempting to send it. What size controls is *budget claim*: each record is charged
     * at most [CrashRetentionPolicy.maxRecordBytes], so one outsized payload cannot displace
     * the rest of the backlog. A record that does not fit the remaining budget is skipped
     * rather than treated as a cutoff, so everything older still gets its chance to fit.
     *
     * [keepName] is the record the caller just wrote, retained regardless of sort position. A
     * backwards clock step can otherwise make a fresh record look oldest and get it deleted by
     * the very write that created it.
     *
     * [nowMs] bounds how new a record is allowed to sort. A future write time would otherwise
     * sort ahead of every genuine record and hold a keep slot against the whole backlog.
     * Ordinary future dates are clamped to [nowMs]; one far enough ahead to be unrecoverable
     * — the same judgement [selectExpiredOwned] makes — sorts last instead, so it is evicted
     * before any record that could still be uploaded. Ordering does not assume an expiry pass
     * has run, because the write path enforces caps on its own.
     */
    fun selectOverflowOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        keepName: String? = null,
        policy: CrashRetentionPolicy = defaultPolicy,
    ): List<CrashDirEntry> {
        // Ties break on the millis embedded in the name, which preserves write order when the
        // filesystem reports a coarser timestamp. Names that do not parse sort last within
        // their timestamp group.
        fun sortKey(entry: CrashDirEntry): Long =
            if (isUnrecoverablyFutureDated(entry, nowMs, policy)) {
                Long.MIN_VALUE
            } else {
                minOf(entry.lastModifiedMs, nowMs)
            }

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
     * True when the directory is already within both accumulation bounds, so a caller on a
     * crash path can skip sorting entirely. Uses the same capped claim as
     * [selectOverflowOwned] so the two can never disagree about whether work is needed.
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
     * Human-readable inventory line for rollout verification, with per-file detail capped at
     * [maxSample] so logs are not flooded. A negative [maxSample] is treated as zero rather
     * than throwing — this runs on a crash-adjacent path where a logging helper must not be
     * the thing that fails.
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
     * True when [entry] is dated so far ahead of [nowMs] that it can no longer be explained
     * by a clock step, and so can never become readable.
     *
     * Platform stores gate reads on `nowMs - lastModifiedMs >= minAgeMillis`, which a future
     * timestamp never satisfies. Using the retention window as the threshold is what keeps
     * the backwards-clock protection intact: a record dated modestly ahead of now is left to
     * wait until the clock agrees it is old, and only one that would still be in the future
     * after the entire window has elapsed is written off.
     */
    private fun isUnrecoverablyFutureDated(
        entry: CrashDirEntry,
        nowMs: Long,
        policy: CrashRetentionPolicy,
    ): Boolean = entry.lastModifiedMs - nowMs > policy.maxReadAgeMillis

    /** Leading millis of a `{millis}-{uuid}.otlp` name, or null for anything else. */
    private fun leadingMillis(name: String): Long? = name.substringBefore('-').toLongOrNull()
}
