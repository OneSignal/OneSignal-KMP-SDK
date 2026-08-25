package com.onesignal.logger.crash

/**
 * One entry in a platform's crash directory, reduced to the three facts retention decisions
 * need. Platforms build these from their own file APIs; everything downstream is shared.
 *
 * @property name platform-opaque identifier, typically the file name. Ownership is decided
 *   from its suffix, so it must be the real on-disk name and not a display label.
 * @property lastModifiedMs write time in epoch millis.
 * @property lengthBytes size on disk; only used for budget accounting.
 */
data class CrashDirEntry(
    val name: String,
    val lastModifiedMs: Long,
    val lengthBytes: Long = 0L,
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
 * Sizing note: these are defaults, exposed as parameters on every selector. A platform whose
 * payload profile or cache-eviction pressure differs can pass its own without forking the
 * algorithms.
 */
object CrashRetention {
    /** Suffix marking a record this SDK wrote. Anything else in the directory is foreign. */
    val ownedSuffixDefault: String = ".otlp"

    /**
     * How long an owned record stays eligible for upload. Carried over from the disk-buffering
     * configuration the OpenTelemetry pipeline used. Past this the payload is too stale to be
     * worth shipping, and the ceiling is what stops a permanently-failing record from being
     * retried forever.
     */
    val maxReadAgeMillis: Long = 72L * 60 * 60 * 1000

    /**
     * Record-count ceiling. This is the bound that normally binds: crash records are
     * single-event OTLP payloads of a few KB, so this covers far more unsent crashes than a
     * healthy install will ever accumulate.
     */
    val maxRecordCount: Int = 50

    /**
     * Byte budget across all owned records, as a backstop for payload profiles the count cap
     * alone would not contain.
     *
     * This bounds *claim*, not bytes on disk. Since writes are size-limited by
     * [maxRecordBytes] the two coincide for anything written by a build enforcing that
     * limit. They diverge only for records inherited from one that did not: each claims at
     * most [maxRecordBytes], so a few oversized leftovers can occupy more than this while
     * still counting as within cap. That is deliberate — they are real crashes and deserve an
     * upload attempt — and it stays bounded by [maxRecordCount] and by [maxReadAgeMillis]
     * aging them out.
     */
    val maxTotalBytes: Long = 2L * 1024 * 1024

    /**
     * Largest payload a store should write. Refusing at the source is what makes "every stored
     * record fits the shared budget" an invariant; without it an outsized payload is either
     * kept at the cost of everything else, or written and then deleted before it can be sent.
     */
    val maxRecordBytes: Long = 512L * 1024

    /** True when [name] is a record this SDK wrote. */
    fun isOwned(name: String, ownedSuffix: String = ownedSuffixDefault): Boolean = name.endsWith(ownedSuffix)

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
        ownedSuffix: String = ownedSuffixDefault,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            !isOwned(entry.name, ownedSuffix) && nowMs - entry.lastModifiedMs >= minAgeMillis
        }

    /**
     * Owned entries past [maxAgeMillis]. These are no longer worth uploading, so they are
     * reclaimed rather than merely hidden from readers — hiding alone would let them
     * accumulate indefinitely.
     *
     * A negative age (write time in the future, i.e. the clock moved backwards since the
     * record was written) is never treated as expired; the record simply waits until the clock
     * agrees it is old.
     */
    fun selectExpiredOwned(
        entries: List<CrashDirEntry>,
        nowMs: Long,
        maxAgeMillis: Long = maxReadAgeMillis,
        ownedSuffix: String = ownedSuffixDefault,
    ): List<CrashDirEntry> =
        entries.filter { entry ->
            isOwned(entry.name, ownedSuffix) && nowMs - entry.lastModifiedMs > maxAgeMillis
        }

    /**
     * Owned entries to evict so the directory fits [maxCount] and [maxTotalBytes]. Newest are
     * kept; the excess is returned oldest-first so callers can delete in that order.
     *
     * Size is never on its own a reason to evict. A payload too large to upload should be
     * refused at write time; deleting one already on disk destroys a captured crash without
     * ever attempting to send it. What size controls is *budget claim*: each record is charged
     * at most [maxRecordBytes], so one outsized payload cannot displace the rest of the
     * backlog. A record that does not fit the remaining budget is skipped rather than treated
     * as a cutoff, so everything older still gets its chance to fit.
     *
     * [keepName] is the record the caller just wrote, retained regardless of sort position. A
     * backwards clock step can otherwise make a fresh record look oldest and get it deleted by
     * the very write that created it.
     */
    @Suppress("LongParameterList")
    fun selectOverflowOwned(
        entries: List<CrashDirEntry>,
        maxCount: Int = maxRecordCount,
        maxTotalBytes: Long = CrashRetention.maxTotalBytes,
        maxRecordBytes: Long = CrashRetention.maxRecordBytes,
        keepName: String? = null,
        ownedSuffix: String = ownedSuffixDefault,
    ): List<CrashDirEntry> {
        // Ties break on the millis embedded in the name, which preserves write order when the
        // filesystem reports a coarser timestamp. Names that do not parse sort last within
        // their timestamp group.
        val newestFirst =
            entries
                .filter { isOwned(it.name, ownedSuffix) }
                .sortedWith(
                    compareByDescending<CrashDirEntry> { it.lastModifiedMs }
                        .thenByDescending { leadingMillis(it.name) ?: Long.MIN_VALUE },
                )

        fun budgetClaim(entry: CrashDirEntry): Long = minOf(entry.lengthBytes, maxRecordBytes)

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
            if (kept.size >= maxCount) break
            if (keptBytes + budgetClaim(entry) > maxTotalBytes) continue
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
        maxCount: Int = maxRecordCount,
        maxTotalBytes: Long = CrashRetention.maxTotalBytes,
        maxRecordBytes: Long = CrashRetention.maxRecordBytes,
        ownedSuffix: String = ownedSuffixDefault,
    ): Boolean {
        val owned = entries.filter { isOwned(it.name, ownedSuffix) }
        val claimed = owned.sumOf { minOf(it.lengthBytes, maxRecordBytes) }
        return owned.size <= maxCount && claimed <= maxTotalBytes
    }

    /**
     * Human-readable inventory line for rollout verification, with per-file detail capped at
     * [maxSample] so logs are not flooded.
     */
    fun formatInventory(
        label: String,
        path: String,
        entries: List<CrashDirEntry>,
        nowMs: Long,
        maxSample: Int,
        ownedSuffix: String = ownedSuffixDefault,
    ): String {
        if (entries.isEmpty()) {
            return "OneSignal: Crash storage inventory [$label] ($path): empty"
        }
        val otlp = entries.count { isOwned(it.name, ownedSuffix) }
        val legacy = entries.size - otlp
        val summary =
            entries.take(maxSample).joinToString(separator = "; ") { entry ->
                "name=${entry.name} bytes=${entry.lengthBytes} ageMs=${nowMs - entry.lastModifiedMs}"
            }
        val truncated =
            if (entries.size > maxSample) " …(+${entries.size - maxSample} more)" else ""
        return "OneSignal: Crash storage inventory [$label] ($path): " +
            "total=${entries.size} otlp=$otlp legacy=$legacy [$summary]$truncated"
    }

    /** Leading millis of a `{millis}-{uuid}.otlp` name, or null for anything else. */
    private fun leadingMillis(name: String): Long? = name.substringBefore('-').toLongOrNull()
}
