package com.onesignal.logger.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retention is pure decision logic, so it is exercised here with fixed timestamps and no
 * filesystem. Platform stores are then only responsible for turning a directory listing into
 * [CrashDirEntry]s and applying the returned decisions.
 */
class CrashRetentionTest {
    // A real epoch reading, not a small number: ages beyond the retention window have to stay
    // on the positive side of the epoch, since a non-positive write time means "unreadable".
    private val now = 1_784_621_689_841L
    private val policy = CrashRetention.defaultPolicy

    private fun owned(name: String, ageMs: Long, bytes: Long = 1L) =
        CrashDirEntry(name, lastModifiedMs = now - ageMs, lengthBytes = bytes)

    private fun foreign(name: String, ageMs: Long, bytes: Long = 1L) =
        CrashDirEntry(name, lastModifiedMs = now - ageMs, lengthBytes = bytes)

    /**
     * A record whose attributes cannot be read but whose name still carries its write time —
     * the shape both stores produce, and what an Apple device lists before first unlock.
     */
    private fun undatedFile(ageMs: Long, tag: String = "a", bytes: Long = 1L) =
        CrashDirEntry("${now - ageMs}-$tag${policy.ownedSuffix}", lastModifiedMs = null, lengthBytes = bytes)

    /** Neither source can date this: attributes unreadable and no millis in the name. */
    private fun undatable(name: String, bytes: Long = 1L) =
        CrashDirEntry(name, lastModifiedMs = null, lengthBytes = bytes)

    /** An interrupted write with unreadable attributes: foreign, but its own name still dates it. */
    private fun interruptedWrite(ageMs: Long) =
        CrashDirEntry("${now - ageMs}-a${policy.ownedTempSuffix}", lastModifiedMs = null, lengthBytes = 1)

    // ===== ownership =====

    @Test
    fun isOwned_recognizes_only_the_owned_suffix() {
        assertTrue(CrashRetention.isOwned("123-abc.otlp"))
        assertFalse(CrashRetention.isOwned("1784621689841"))
        assertFalse(CrashRetention.isOwned("stale.tmp"))
        assertFalse(CrashRetention.isOwned("123-abc.otlp.tmp"))
    }

    // ===== effective write time =====

    @Test
    fun effectiveWriteTimeMs_prefers_the_filesystem_over_the_name() {
        // The filesystem is the only one of the two that follows a rewrite, so it decides
        // whenever it is readable and the name is a fallback rather than a second opinion.
        val entry =
            CrashDirEntry("${now - 900_000}-a.otlp", lastModifiedMs = now - 1_000, lengthBytes = 1)

        assertEquals(now - 1_000, CrashRetention.effectiveWriteTimeMs(entry))
    }

    @Test
    fun effectiveWriteTimeMs_falls_back_to_the_millis_in_the_name() {
        assertEquals(now - 60_000, CrashRetention.effectiveWriteTimeMs(undatedFile(ageMs = 60_000)))
    }

    @Test
    fun effectiveWriteTimeMs_treats_a_non_positive_filesystem_time_as_unreadable() {
        // `File.lastModified()` and a nil `contentModificationDate` both used to arrive as 0,
        // which read back as maximum age. Nothing this SDK writes predates the epoch.
        val name = "${now - 60_000}-a.otlp"

        assertEquals(
            now - 60_000,
            CrashRetention.effectiveWriteTimeMs(CrashDirEntry(name, lastModifiedMs = 0, lengthBytes = 1)),
        )
        assertEquals(
            now - 60_000,
            CrashRetention.effectiveWriteTimeMs(CrashDirEntry(name, lastModifiedMs = -1, lengthBytes = 1)),
        )
    }

    @Test
    fun effectiveWriteTimeMs_is_null_when_neither_source_can_date_the_entry() {
        assertNull(CrashRetention.effectiveWriteTimeMs(undatable("crash-report.otlp")))
    }

    @Test
    fun effectiveWriteTimeMs_treats_a_non_positive_name_time_as_unreadable_too() {
        // The screen has to cover the fallback, not just the filesystem: a leading `0` read as
        // an epoch reading is maximum age, which is the deletion this whole path exists to stop.
        val entry = CrashDirEntry("0-abc.otlp", lastModifiedMs = null, lengthBytes = 1)

        assertNull(CrashRetention.effectiveWriteTimeMs(entry))
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(listOf(entry), nowMs = now))
    }

    @Test
    fun effectiveWriteTimeMs_does_not_date_a_foreign_name_that_is_not_bare_millis() {
        // Legacy otel names are bare millis; `3-tmp.dat` belongs to some other writer's scheme.
        // Reading its `3` as an epoch time makes a file written seconds ago look reapable.
        val entry = CrashDirEntry("3-tmp.dat", lastModifiedMs = null, lengthBytes = 1)

        assertNull(CrashRetention.effectiveWriteTimeMs(entry))
        assertEquals(
            emptyList(),
            CrashRetention.selectUnrecognized(listOf(entry), nowMs = now, minAgeMillis = 5_000),
        )
    }

    @Test
    fun an_interrupted_write_stays_datable_while_a_foreign_name_of_the_same_shape_does_not() {
        // Both stores name the temp file `{millis}-{uuid}.otlp.tmp`. Undatable it is foreign
        // with no age, so `selectUnrecognized` — the only pass that reaps it — never can, and
        // an interrupted write is stranded for the life of the install. Attributes unreadable
        // is the case that matters: that is every Apple entry before first unlock.
        val interrupted = interruptedWrite(ageMs = 600_000)
        val otherWriter = undatable("3-tmp.dat")

        assertEquals(now - 600_000, CrashRetention.effectiveWriteTimeMs(interrupted))
        assertNull(CrashRetention.effectiveWriteTimeMs(otherWriter))

        val reaped =
            CrashRetention.selectUnrecognized(
                listOf(interrupted, otherWriter),
                nowMs = now,
                minAgeMillis = 5_000,
            )

        assertEquals(listOf(interrupted.name), reaped.map { it.name })
    }

    @Test
    fun an_interrupted_write_is_still_foreign_and_still_protected_by_the_age_gate() {
        // Datable must not mean owned: a half-written record can never be read, and it must not
        // hold a count slot or budget against complete ones. And the write that just started is
        // dated seconds ago, so the gate protects it from the sweep that reaps its stale siblings.
        val fresh = interruptedWrite(ageMs = 100)

        assertFalse(CrashRetention.isOwned(fresh.name))
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(listOf(fresh), nowMs = now))
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(listOf(fresh), nowMs = now))
        assertEquals(
            emptyList(),
            CrashRetention.selectUnrecognized(listOf(fresh), nowMs = now, minAgeMillis = 5_000),
        )
    }

    @Test
    fun effectiveWriteTimeMs_dates_a_record_under_the_caller_s_own_policy() {
        // The public entry point used to hardcode the default policy, so a store with its own
        // suffix had `selectExpiredOwned` date and expire records that its `minAgeMillis` read
        // gate — calling this — saw as permanently undatable and withheld forever.
        val custom = policy.copy(ownedSuffix = ".osrec")
        val entry = CrashDirEntry("${now - 600_000}-abc.osrec", lastModifiedMs = null, lengthBytes = 1)

        assertEquals(now - 600_000, CrashRetention.effectiveWriteTimeMs(entry, custom))
        assertEquals(
            listOf(entry.name),
            CrashRetention.selectExpiredOwned(
                listOf(entry),
                nowMs = now + policy.maxReadAgeMillis,
                policy = custom,
            ).map { it.name },
        )
    }

    // ===== foreign entries =====

    @Test
    fun selectUnrecognized_keeps_owned_and_too_young_foreign_files() {
        val entries =
            listOf(
                owned("123-abc.otlp", ageMs = 60_000),
                foreign("too-young-legacy", ageMs = 100),
                foreign("stale-legacy", ageMs = 10_000),
                foreign("stale.tmp", ageMs = 60_000),
            )

        val selected = CrashRetention.selectUnrecognized(entries, nowMs = now, minAgeMillis = 5_000)

        assertEquals(listOf("stale-legacy", "stale.tmp"), selected.map { it.name })
    }

    @Test
    fun selectUnrecognized_is_empty_when_only_owned_records_exist() {
        val entries = listOf(owned("123-abc.otlp", ageMs = 60_000))

        assertEquals(emptyList(), CrashRetention.selectUnrecognized(entries, now, minAgeMillis = 0))
    }

    @Test
    fun selectUnrecognized_dates_a_legacy_bare_millis_name_from_the_name_itself() {
        // A pre-upgrade otel name parses under the same rule. Harmless — ownership is decided
        // by suffix, so it stays foreign — and it is the only pass that ever reclaims these,
        // so an unreadable timestamp must not exempt them.
        val entries =
            listOf(
                CrashDirEntry("${now - 60_000}", lastModifiedMs = null, lengthBytes = 1),
                CrashDirEntry("${now - 100}", lastModifiedMs = null, lengthBytes = 1),
            )

        val selected = CrashRetention.selectUnrecognized(entries, nowMs = now, minAgeMillis = 5_000)

        assertEquals(listOf("${now - 60_000}"), selected.map { it.name })
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    @Test
    fun selectUnrecognized_leaves_an_undatable_foreign_file_alone() {
        // The age gate exists to protect another writer's in-flight file. An entry that cannot
        // be measured against it has not cleared it.
        val entries = listOf(undatable("stale.tmp"))

        assertEquals(emptyList(), CrashRetention.selectUnrecognized(entries, nowMs = now, minAgeMillis = 5_000))
    }

    // ===== expiry =====

    @Test
    fun selectExpiredOwned_takes_only_owned_records_strictly_past_the_ceiling() {
        val entries =
            listOf(
                owned("1-a.otlp", ageMs = policy.maxReadAgeMillis + 1),
                owned("2-b.otlp", ageMs = policy.maxReadAgeMillis - 1),
                foreign("legacy", ageMs = policy.maxReadAgeMillis * 2),
            )

        val expired = CrashRetention.selectExpiredOwned(entries, nowMs = now)

        assertEquals(listOf("1-a.otlp"), expired.map { it.name })
    }

    @Test
    fun selectExpiredOwned_treats_a_record_at_exactly_the_ceiling_as_readable() {
        val entries = listOf(owned("1-a.otlp", ageMs = policy.maxReadAgeMillis))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    @Test
    fun selectExpiredOwned_ignores_a_plausible_backwards_clock_step() {
        // The clock moved back an hour since the record was written. It is a real, recent,
        // uploadable crash — it just has to wait for the clock to agree it is old.
        val entries = listOf(owned("1-a.otlp", ageMs = -60L * 60 * 1000))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    @Test
    fun selectExpiredOwned_reclaims_a_record_dated_past_the_window_into_the_future() {
        // Beyond a full retention window ahead of now, no clock correction brings it back:
        // the platform read gate (now - mtime >= minAge) can never pass, so the record is
        // unreadable for life while still holding a count slot and budget. Expiry is the
        // only thing that will ever remove it.
        val entries = listOf(owned("1-a.otlp", ageMs = -(policy.maxReadAgeMillis + 1)))

        assertEquals(listOf("1-a.otlp"), CrashRetention.selectExpiredOwned(entries, nowMs = now).map { it.name })
    }

    @Test
    fun selectExpiredOwned_leaves_a_record_exactly_one_window_into_the_future() {
        // The boundary belongs to the backwards-clock case, matching the past-side ceiling.
        val entries = listOf(owned("1-a.otlp", ageMs = -policy.maxReadAgeMillis))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    @Test
    fun selectExpiredOwned_is_empty_for_an_empty_directory() {
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(emptyList(), nowMs = now))
    }

    @Test
    fun selectExpiredOwned_retains_a_recent_record_whose_timestamp_is_unreadable() {
        // The data-loss case. An Apple device lists this directory before first unlock with
        // attributes unreadable, on every reboot; a minute-old crash captured before that
        // reboot must survive to be uploaded, not be reclaimed as maximally old.
        val entries =
            listOf(
                undatedFile(ageMs = 60_000, tag = "nil"),
                CrashDirEntry("${now - 60_000}-zero.otlp", lastModifiedMs = 0, lengthBytes = 1),
            )

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    @Test
    fun selectExpiredOwned_still_expires_a_stale_record_whose_timestamp_is_unreadable() {
        // The other direction: the name dates it past the ceiling, so the age bound keeps
        // applying instead of quietly deferring to accumulation pressure.
        val entries = listOf(undatedFile(ageMs = policy.maxReadAgeMillis + 1))

        assertEquals(listOf(entries[0].name), CrashRetention.selectExpiredOwned(entries, nowMs = now).map { it.name })
    }

    @Test
    fun selectExpiredOwned_never_expires_a_record_it_cannot_date_at_all() {
        // No timestamp and no millis in the name. A failed read is not evidence of age, so
        // this is left to the accumulation caps rather than deleted.
        val entries = listOf(undatable("crash-report.otlp"))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    @Test
    fun selectExpiredOwned_writes_off_a_name_dated_unrecoverably_into_the_future() {
        // Platforms gate reads on the same effective time, so a name this far ahead can never
        // pass one — it would hold a count slot for life while being unreadable.
        val entries = listOf(undatedFile(ageMs = -(policy.maxReadAgeMillis + 1)))

        assertEquals(listOf(entries[0].name), CrashRetention.selectExpiredOwned(entries, nowMs = now).map { it.name })
    }

    @Test
    fun selectExpiredOwned_tolerates_a_name_dated_modestly_into_the_future() {
        // Ordinary clock skew between the writing session and this one. The record is real and
        // recent; it just waits for the clock to agree it is old.
        val entries = listOf(undatedFile(ageMs = -60_000))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    // ===== accumulation =====

    @Test
    fun selectOverflowOwned_returns_nothing_while_within_both_caps() {
        val entries = (1..3).map { owned("$it-a.otlp", ageMs = it * 1_000L) }

        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(entries, nowMs = now))
    }

    @Test
    fun selectOverflowOwned_evicts_oldest_first_past_the_count_cap() {
        val max = policy.maxRecordCount
        val entries = (1..max + 2).map { owned("$it-a.otlp", ageMs = it * 1_000L) }

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now)

        // Larger index means older, and the result is ordered oldest-first.
        assertEquals(listOf("${max + 2}-a.otlp", "${max + 1}-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_never_touches_foreign_entries() {
        val max = policy.maxRecordCount
        val entries =
            (1..max + 1).map { owned("$it-a.otlp", ageMs = it * 1_000L) } +
                foreign("legacy", ageMs = 999_000)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now)

        assertFalse(evicted.any { it.name == "legacy" })
    }

    @Test
    fun an_oversized_record_is_retained_and_cannot_displace_the_rest() {
        // Size alone is never grounds for eviction: deleting a captured crash without ever
        // attempting to upload it is worse than keeping it. Size only caps budget claim.
        val entries =
            listOf(
                owned("5-newest.otlp", ageMs = 1_000, bytes = 10),
                owned("4-huge.otlp", ageMs = 2_000, bytes = policy.maxTotalBytes * 2),
                owned("3-small.otlp", ageMs = 3_000, bytes = 10),
                owned("2-small.otlp", ageMs = 4_000, bytes = 10),
            )

        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(entries, nowMs = now))
    }

    @Test
    fun a_record_that_does_not_fit_the_remaining_budget_is_skipped_not_treated_as_a_cutoff() {
        // Four near-cap records fill most of the budget. The next cannot fit, but a smaller
        // and *older* one still can — proving the loop skips rather than stopping.
        val nearCap = policy.maxRecordBytes - 12_288
        val entries =
            (1..4).map { owned("${10 - it}-fills.otlp", ageMs = it * 1_000L, bytes = nearCap) } +
                owned("5-does-not-fit.otlp", ageMs = 5_000, bytes = 200_000) +
                owned("4-still-fits.otlp", ageMs = 6_000, bytes = 40_000)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now)

        assertEquals(listOf("5-does-not-fit.otlp"), evicted.map { it.name })
    }

    @Test
    fun keepNames_retains_the_just_written_record_even_when_it_sorts_oldest() {
        val max = policy.maxRecordCount
        val entries =
            (1..max).map { owned("$it-a.otlp", ageMs = it * 1_000L) } +
                owned("fresh-a.otlp", ageMs = 999_000)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = setOf("fresh-a.otlp"))

        assertFalse(evicted.any { it.name == "fresh-a.otlp" })
        assertEquals(listOf("$max-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun an_oversized_protected_record_does_not_evict_the_pending_backlog() {
        // A protected name claims no share of the budget, so an outsized one in flight cannot
        // push a sibling out of the remaining-budget check.
        val backlog = (1..4).map { owned("$it-small.otlp", ageMs = it * 10_000L, bytes = 400_000) }
        val entries =
            backlog + owned("fresh-a.otlp", ageMs = 1_000, bytes = policy.maxTotalBytes * 2)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = setOf("fresh-a.otlp"))

        assertEquals(emptyList(), evicted)
    }

    @Test
    fun equal_timestamps_break_the_tie_on_the_millis_embedded_in_the_name() {
        // Coarse filesystem timestamps collapse mtimes; the name preserves write order.
        val entries =
            listOf(
                CrashDirEntry("100-a.otlp", lastModifiedMs = now, lengthBytes = 10),
                CrashDirEntry("300-c.otlp", lastModifiedMs = now, lengthBytes = 10),
                CrashDirEntry("200-b.otlp", lastModifiedMs = now, lengthBytes = 10),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2))

        assertEquals(listOf("100-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun a_future_dated_record_is_evicted_before_any_record_that_could_still_upload() {
        // The write path enforces caps without running expiry first, so ordering has to make
        // this call on its own. Left unranked, the future record sorts newest, keeps its slot
        // forever, and pushes out genuine records that are still uploadable.
        val entries =
            listOf(
                owned("9-zombie.otlp", ageMs = -(policy.maxReadAgeMillis + 1)),
                owned("300-a.otlp", ageMs = 1_000),
                owned("200-b.otlp", ageMs = 2_000),
                owned("100-c.otlp", ageMs = 3_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2))

        assertEquals(listOf("9-zombie.otlp", "100-c.otlp"), evicted.map { it.name })
    }

    @Test
    fun a_modestly_future_record_still_ranks_among_the_newest() {
        // Only an unrecoverable date is written off. An ordinary backwards clock step leaves
        // a real, recent record that must keep its place ahead of older ones.
        val entries =
            listOf(
                owned("9-clock-skew.otlp", ageMs = -60_000),
                owned("300-a.otlp", ageMs = 1_000),
                owned("100-c.otlp", ageMs = 3_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2))

        assertEquals(listOf("100-c.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_is_empty_for_an_empty_directory() {
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(emptyList(), nowMs = now))
    }

    // ===== accumulation: records whose timestamp is unreadable =====

    @Test
    fun selectOverflowOwned_ranks_an_undated_record_by_the_millis_in_its_name() {
        // Its name places it newest, so it keeps its slot ahead of two records the filesystem
        // could date — an unreadable timestamp costs it no standing.
        val entries =
            listOf(
                owned("300-a.otlp", ageMs = 5_000),
                undatedFile(ageMs = 1_000, tag = "undated"),
                owned("100-c.otlp", ageMs = 10_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2))

        assertEquals(listOf("100-c.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_evicts_a_name_dated_unrecoverably_into_the_future_first() {
        val zombie = undatedFile(ageMs = -(policy.maxReadAgeMillis + 1), tag = "zombie")
        val entries =
            listOf(
                zombie,
                owned("300-a.otlp", ageMs = 1_000),
                owned("200-b.otlp", ageMs = 2_000),
                owned("100-c.otlp", ageMs = 3_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2))

        assertEquals(listOf(zombie.name, "100-c.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_does_not_privilege_the_more_absurdly_future_name() {
        // Both names are ahead of now, so both sort as "now" and listing order decides. Left
        // uncapped, the one further ahead would outrank the other purely for being wronger.
        val near = undatedFile(ageMs = -10_000, tag = "near")
        val far = undatedFile(ageMs = -200_000, tag = "far")

        val evicted =
            CrashRetention.selectOverflowOwned(
                listOf(near, far),
                nowMs = now,
                policy = policy.copy(maxRecordCount = 1),
            )

        assertEquals(listOf(far.name), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_evicts_an_undatable_record_before_one_it_can_date() {
        // Not evidence the record is worthless, but it has no claim on a slot against a record
        // we can actually place in time.
        val entries =
            listOf(
                undatable("crash-x.otlp"),
                owned("300-a.otlp", ageMs = 1_000),
                owned("100-c.otlp", ageMs = 3_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2))

        assertEquals(listOf("crash-x.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_still_evicts_records_it_cannot_date() {
        // Boundedness does not depend on being able to date anything: an entry left out of the
        // caps would be outside every bound at once and leak for the life of the install.
        val entries = ('a'..'d').map { undatable("crash-$it.otlp") }

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2))

        assertEquals(2, evicted.size)
    }

    @Test
    fun selectOverflowOwned_trims_the_oldest_when_no_timestamp_is_readable() {
        // Every entry at once, which is what an Apple device lists before first unlock. The
        // millis in the names still order them, so the trim is by write order, not by luck.
        val ages = listOf(3_000L, 1_000L, 5_000L, 2_000L, 4_000L)
        val entries = ages.mapIndexed { index, ageMs -> undatedFile(ageMs = ageMs, tag = "r$index") }

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 3))

        assertEquals(listOf(entries[2].name, entries[4].name), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_trims_only_the_excess_when_nothing_can_be_dated() {
        // The degenerate case: no timestamps and no millis in any name. Only the overflow goes,
        // in listing order — the directory is not wiped for being undatable.
        val entries = ('a'..'e').map { undatable("crash-$it.otlp") }

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 3))

        assertEquals(listOf("crash-e.otlp", "crash-d.otlp"), evicted.map { it.name })
    }

    // ===== accumulation: protected names =====

    @Test
    fun keepNames_protects_every_name_in_the_set_at_once() {
        // One protected name is not enough: concurrent crashing threads each hold a record
        // open, and evicting any of them corrupts a crash being captured right now.
        val max = policy.maxRecordCount
        val entries =
            (1..max).map { owned("$it-a.otlp", ageMs = it * 1_000L) } +
                owned("first-flight.otlp", ageMs = 900_000) +
                owned("second-flight.otlp", ageMs = 800_000)

        val evicted =
            CrashRetention.selectOverflowOwned(
                entries,
                nowMs = now,
                keepNames = setOf("first-flight.otlp", "second-flight.otlp"),
            )

        assertEquals(listOf("$max-a.otlp", "${max - 1}-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun keepNames_are_retained_even_when_the_set_alone_exceeds_the_caps() {
        // Deliberate: the caps may be overshot while writes are in flight. The alternative is
        // deleting a file another thread still has open. The overshoot ends when those writes
        // finish and the next scan trims normally.
        val inFlight = (1..3).map { owned("flight-$it.otlp", ageMs = it * 1_000L) }
        val settled = (1..2).map { owned("$it-a.otlp", ageMs = 10_000L * it) }

        val evicted =
            CrashRetention.selectOverflowOwned(
                inFlight + settled,
                nowMs = now,
                keepNames = inFlight.mapTo(HashSet()) { it.name },
                policy = policy.copy(maxRecordCount = 2),
            )

        assertEquals(setOf("1-a.otlp", "2-a.otlp"), evicted.map { it.name }.toSet())
    }

    @Test
    fun protected_records_claiming_the_whole_budget_do_not_evict_the_backlog() {
        // Four concurrent in-flight writes at the per-record ceiling claim exactly maxTotalBytes.
        // Charged against the shared budget they left nothing for anything else, so every
        // pending record failed the fit check and the entire backlog was returned for eviction.
        val inFlight =
            (1..4).map { owned("flight-$it.otlp", ageMs = it * 1_000L, bytes = policy.maxRecordBytes) }
        val backlog = (1..6).map { owned("$it-pending.otlp", ageMs = it * 100_000L, bytes = 1_000) }

        val evicted =
            CrashRetention.selectOverflowOwned(
                inFlight + backlog,
                nowMs = now,
                keepNames = inFlight.mapTo(HashSet()) { it.name },
            )

        assertEquals(emptyList(), evicted)
    }

    @Test
    fun the_unprotected_pass_keeps_its_own_byte_budget_while_names_are_protected() {
        // Protected claims are excused, not the budget itself: the backlog is still trimmed to
        // maxTotalBytes, so excusing them cannot turn into an unbounded directory.
        val inFlight =
            (1..4).map { owned("flight-$it.otlp", ageMs = it * 1_000L, bytes = policy.maxRecordBytes) }
        val backlog =
            (1..5).map { owned("$it-pending.otlp", ageMs = it * 100_000L, bytes = policy.maxRecordBytes) }

        val evicted =
            CrashRetention.selectOverflowOwned(
                inFlight + backlog,
                nowMs = now,
                keepNames = inFlight.mapTo(HashSet()) { it.name },
            )

        assertEquals(listOf("5-pending.otlp"), evicted.map { it.name })
    }

    // ===== composition: both passes must run; order does not decide survivors =====

    @Test
    fun overflow_after_expiry_reclaims_nothing_when_the_survivors_fit() {
        val expired =
            (1..policy.maxRecordCount).map {
                owned("expired-$it.otlp", ageMs = policy.maxReadAgeMillis + it * 1_000L)
            }
        val fresh = (1..5).map { owned("fresh-$it.otlp", ageMs = it * 1_000L) }
        val listing = expired + fresh

        val expiredNames = CrashRetention.selectExpiredOwned(listing, nowMs = now).map { it.name }.toSet()
        val survivors = listing.filterNot { it.name in expiredNames }

        assertEquals(policy.maxRecordCount, expiredNames.size)
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(survivors, nowMs = now))
    }

    @Test
    fun overflow_alone_sheds_an_expired_record_before_one_it_cannot_date() {
        // The write path runs overflow and no expiry, so ranking has to make this call alone.
        // A record past the read ceiling is known unuploadable; an undatable one may still be a
        // live crash. Ranked equal, the count cap kept the dead one and dropped the live one.
        val listing =
            (1..49).map { owned("fresh-$it.otlp", ageMs = it * 1_000L) } +
                owned("stale.otlp", ageMs = policy.maxReadAgeMillis + 60_000) +
                undatable("crash-x.otlp")

        val evicted = CrashRetention.selectOverflowOwned(listing, nowMs = now)

        assertEquals(listOf("stale.otlp"), evicted.map { it.name })

        // And that is exactly what running expiry first would have left.
        val expiredNames = CrashRetention.selectExpiredOwned(listing, nowMs = now).map { it.name }.toSet()
        assertEquals(setOf("stale.otlp"), expiredNames)
        assertEquals(
            emptyList(),
            CrashRetention.selectOverflowOwned(listing.filterNot { it.name in expiredNames }, nowMs = now),
        )
    }

    @Test
    fun ordering_the_two_passes_does_not_change_which_live_records_survive() {
        // Overflow ranks expired entries below everything still uploadable, so feeding it the
        // raw listing costs no *additional* live record over running expiry first — the live
        // records lost to the count cap are the same either way, and the extra entries it
        // returns are ones expiry would have removed.
        // Pinned because the contract used to claim the raw listing evicted live records.
        val expired =
            (1..100).map { owned("expired-$it.otlp", ageMs = policy.maxReadAgeMillis + it * 1_000L) }
        val fresh = (1..60).map { owned("fresh-$it.otlp", ageMs = it * 1_000L) }
        val listing = expired + fresh

        val fromRaw = CrashRetention.selectOverflowOwned(listing, nowMs = now)
        val expiredNames = CrashRetention.selectExpiredOwned(listing, nowMs = now).map { it.name }.toSet()
        val fromSurvivors =
            CrashRetention.selectOverflowOwned(listing.filterNot { it.name in expiredNames }, nowMs = now)

        val liveFromRaw = fromRaw.filter { it.name.startsWith("fresh-") }
        val liveFromSurvivors = fromSurvivors.filter { it.name.startsWith("fresh-") }

        // 60 live against a cap of 50: the same 10 go either way.
        assertEquals(10, liveFromRaw.size)
        assertEquals(liveFromRaw.map { it.name }, liveFromSurvivors.map { it.name })
    }

    @Test
    fun overflow_alone_leaves_an_expired_record_that_fits_under_the_caps() {
        // Why both passes are required rather than just overflow: nothing about being over-age
        // reclaims a record if the directory is within its accumulation bounds.
        val listing = listOf(owned("stale.otlp", ageMs = policy.maxReadAgeMillis + 60_000))

        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(listing, nowMs = now))
        assertEquals(listOf("stale.otlp"), CrashRetention.selectExpiredOwned(listing, nowMs = now).map { it.name })
    }

    // ===== cheap-path agreement =====

    @Test
    fun isWithinCaps_agrees_with_the_selector() {
        val within = (1..3).map { owned("$it-a.otlp", ageMs = it * 1_000L) }
        assertTrue(CrashRetention.isWithinCaps(within))
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(within, nowMs = now))

        val overCount = (1..policy.maxRecordCount + 1).map { owned("$it-a.otlp", ageMs = it * 1_000L) }
        assertFalse(CrashRetention.isWithinCaps(overCount))
        assertTrue(CrashRetention.selectOverflowOwned(overCount, nowMs = now).isNotEmpty())
    }

    @Test
    fun isWithinCaps_reports_over_budget_while_the_count_is_still_within_cap() {
        // The byte branch on its own. Five records at the per-record ceiling claim 2.5 MiB
        // against a 2 MiB budget, with the count nowhere near its cap — so a caller that
        // only checked the count would skip a trim the selector says is needed.
        val entries =
            (1..5).map { owned("$it-a.otlp", ageMs = it * 1_000L, bytes = policy.maxRecordBytes) }

        assertTrue(entries.size <= policy.maxRecordCount)
        assertFalse(CrashRetention.isWithinCaps(entries))
        assertTrue(CrashRetention.selectOverflowOwned(entries, nowMs = now).isNotEmpty())
    }

    @Test
    fun isWithinCaps_counts_records_it_cannot_date() {
        // The cheap path has to see the same directory the selector does. An entry left out of
        // the count is outside every bound: uncounted here, and so never trimmed.
        val entries = (1..policy.maxRecordCount + 1).map { undatable("crash-$it.otlp") }

        assertFalse(CrashRetention.isWithinCaps(entries))
        assertTrue(CrashRetention.selectOverflowOwned(entries, nowMs = now).isNotEmpty())
    }

    @Test
    fun isWithinCaps_excuses_protected_claims_exactly_as_the_selector_does() {
        // Five records at 500 KiB against a 2 MiB budget, newest in flight. Charged the
        // protected claim the check reports over cap while the selector, which charges only the
        // four unprotected, keeps everything — so the crash path sorts the whole directory and
        // deletes nothing. Not transient: the newest record is protected on every write.
        val entries =
            (1..5).map { owned("$it-a.otlp", ageMs = it * 1_000L, bytes = 500L * 1024) }
        val inFlight = setOf("1-a.otlp")

        assertTrue(CrashRetention.isWithinCaps(entries, inFlight))
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = inFlight))

        // The control: excusing protected claims is not excusing the budget. The same five
        // records with the write settled are over cap, and the selector agrees.
        assertFalse(CrashRetention.isWithinCaps(entries))
        assertTrue(CrashRetention.selectOverflowOwned(entries, nowMs = now).isNotEmpty())
    }

    @Test
    fun isWithinCaps_counts_protected_records_against_the_record_cap() {
        // They claim no bytes but they do hold count slots, so the cheap exit must not skip a
        // trim the selector would perform.
        val entries = (1..policy.maxRecordCount + 1).map { owned("$it-a.otlp", ageMs = it * 1_000L) }
        val inFlight = setOf("1-a.otlp")

        assertFalse(CrashRetention.isWithinCaps(entries, inFlight))
        assertTrue(CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = inFlight).isNotEmpty())
    }

    @Test
    fun isWithinCaps_charges_oversized_records_only_their_capped_share() {
        // One huge inherited record must not make the directory look over budget on its own,
        // or a crash path would sort and trim on every single write.
        val entries = listOf(owned("huge.otlp", ageMs = 1_000, bytes = policy.maxTotalBytes * 4))

        assertTrue(CrashRetention.isWithinCaps(entries))
    }

    // ===== inventory =====

    @Test
    fun formatInventory_reports_empty_directories() {
        assertEquals(
            "OneSignal: Crash storage inventory [before-upload] (/cache/crashes): empty",
            CrashRetention.formatInventory(
                label = "before-upload",
                path = "/cache/crashes",
                entries = emptyList(),
                nowMs = now,
                maxSample = 20,
            ),
        )
    }

    @Test
    fun formatInventory_counts_owned_versus_foreign_and_bounds_the_sample() {
        val entries =
            (1..25).map { index ->
                val name = if (index <= 3) "$index.otlp" else "legacy-$index"
                CrashDirEntry(name, lastModifiedMs = now - index * 1_000L, lengthBytes = index.toLong())
            }

        val line =
            CrashRetention.formatInventory(
                label = "after-cleanup",
                path = "/cache/crashes",
                entries = entries,
                nowMs = now,
                maxSample = 5,
            )

        assertTrue(line.contains("total=25 otlp=3 legacy=22"), line)
        assertTrue(line.contains("name=1.otlp"), line)
        assertTrue(line.contains("…(+20 more)"), line)
        assertFalse(line.contains("name=legacy-25"), line)
    }

    @Test
    fun formatInventory_treats_a_negative_sample_size_as_zero() {
        // A logging helper on a crash-adjacent path must not be the thing that throws.
        val entries = listOf(owned("1-a.otlp", ageMs = 1_000))

        val line =
            CrashRetention.formatInventory(
                label = "after-cleanup",
                path = "/cache/crashes",
                entries = entries,
                nowMs = now,
                maxSample = -1,
            )

        assertTrue(line.contains("total=1 otlp=1 legacy=0"), line)
        assertTrue(line.contains("…(+1 more)"), line)
    }

    @Test
    fun formatInventory_reports_an_age_it_cannot_compute_as_unknown() {
        // Printing a fabricated age here would make a directory of live records look ancient in
        // the very logs used to verify retention.
        val line =
            CrashRetention.formatInventory(
                label = "before-upload",
                path = "/cache/crashes",
                entries = listOf(undatedFile(ageMs = 60_000), undatable("crash-x.otlp")),
                nowMs = now,
                maxSample = 2,
            )

        assertTrue(line.contains("ageMs=60000"), line)
        assertTrue(line.contains("name=crash-x.otlp bytes=1 ageMs=unknown"), line)
    }
}
