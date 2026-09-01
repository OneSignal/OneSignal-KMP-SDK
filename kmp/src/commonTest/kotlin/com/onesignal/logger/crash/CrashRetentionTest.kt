package com.onesignal.logger.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure decision logic, exercised with fixed timestamps and no filesystem. */
class CrashRetentionTest {
    // A real epoch reading: ages past the retention window must stay positive, since non-positive means unreadable.
    private val now = 1_784_621_689_841L
    private val policy = CrashRetention.defaultPolicy

    private fun owned(name: String, ageMs: Long, bytes: Long = 1L) =
        CrashDirEntry(name, lastModifiedMs = now - ageMs, lengthBytes = bytes)

    private fun foreign(name: String, ageMs: Long, bytes: Long = 1L) =
        CrashDirEntry(name, lastModifiedMs = now - ageMs, lengthBytes = bytes)

    /** Attributes unreadable, name still carrying the write time: what an Apple device lists before first unlock. */
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
        assertTrue(CrashRetention.isOwned("123-abc.otlp", policy = policy))
        assertFalse(CrashRetention.isOwned("1784621689841", policy = policy))
        assertFalse(CrashRetention.isOwned("stale.tmp", policy = policy))
        assertFalse(CrashRetention.isOwned("123-abc.otlp.tmp", policy = policy))
    }

    // ===== effective write time =====

    @Test
    fun effectiveWriteTimeMs_prefers_the_filesystem_over_the_name() {
        val entry =
            CrashDirEntry("${now - 900_000}-a.otlp", lastModifiedMs = now - 1_000, lengthBytes = 1)

        assertEquals(now - 1_000, CrashRetention.effectiveWriteTimeMs(entry, policy = policy))
    }

    @Test
    fun effectiveWriteTimeMs_falls_back_to_the_millis_in_the_name() {
        assertEquals(now - 60_000, CrashRetention.effectiveWriteTimeMs(undatedFile(ageMs = 60_000), policy = policy))
    }

    @Test
    fun effectiveWriteTimeMs_treats_a_non_positive_filesystem_time_as_unreadable() {
        val name = "${now - 60_000}-a.otlp"

        assertEquals(
            now - 60_000,
            CrashRetention.effectiveWriteTimeMs(CrashDirEntry(name, lastModifiedMs = 0, lengthBytes = 1), policy = policy),
        )
        assertEquals(
            now - 60_000,
            CrashRetention.effectiveWriteTimeMs(CrashDirEntry(name, lastModifiedMs = -1, lengthBytes = 1), policy = policy),
        )
    }

    @Test
    fun effectiveWriteTimeMs_is_null_when_neither_source_can_date_the_entry() {
        assertNull(CrashRetention.effectiveWriteTimeMs(undatable("crash-report.otlp"), policy = policy))
    }

    @Test
    fun effectiveWriteTimeMs_treats_a_non_positive_name_time_as_unreadable_too() {
        val entry = CrashDirEntry("0-abc.otlp", lastModifiedMs = null, lengthBytes = 1)

        assertNull(CrashRetention.effectiveWriteTimeMs(entry, policy = policy))
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(listOf(entry), nowMs = now, policy = policy))
    }

    @Test
    fun effectiveWriteTimeMs_does_not_date_a_foreign_name_that_is_not_bare_millis() {
        // Reading the `3` as an epoch time would make another writer's seconds-old file look reapable.
        val entry = CrashDirEntry("3-tmp.dat", lastModifiedMs = null, lengthBytes = 1)

        assertNull(CrashRetention.effectiveWriteTimeMs(entry, policy = policy))
        assertEquals(
            emptyList(),
            CrashRetention.selectUnrecognized(listOf(entry), nowMs = now, minAgeMillis = 5_000, policy = policy),
        )
    }

    @Test
    fun an_interrupted_write_stays_datable_while_a_foreign_name_of_the_same_shape_does_not() {
        // `selectUnrecognized` is the only pass that reaps a stray temp, and it requires an age.
        val interrupted = interruptedWrite(ageMs = 600_000)
        val otherWriter = undatable("3-tmp.dat")

        assertEquals(now - 600_000, CrashRetention.effectiveWriteTimeMs(interrupted, policy = policy))
        assertNull(CrashRetention.effectiveWriteTimeMs(otherWriter, policy = policy))

        val reaped =
            CrashRetention.selectUnrecognized(
                listOf(interrupted, otherWriter),
                nowMs = now,
                minAgeMillis = 5_000,
                policy = policy,
            )

        assertEquals(listOf(interrupted.name), reaped.map { it.name })
    }

    @Test
    fun the_temp_suffix_alone_does_not_make_an_interrupted_write_reclaimable() {
        // The suffix only picks the parser; without leading millis nothing can date the file, so it leaks.
        val prefixed = interruptedWrite(ageMs = 600_000)
        val unprefixed = undatable("scratch${policy.ownedTempSuffix}")

        assertEquals(now - 600_000, CrashRetention.effectiveWriteTimeMs(prefixed, policy = policy))
        assertNull(CrashRetention.effectiveWriteTimeMs(unprefixed, policy = policy))

        val reaped =
            CrashRetention.selectUnrecognized(
                listOf(prefixed, unprefixed),
                nowMs = now,
                minAgeMillis = 5_000,
                policy = policy,
            )

        assertEquals(listOf(prefixed.name), reaped.map { it.name })
    }

    @Test
    fun an_interrupted_write_is_still_foreign_and_still_protected_by_the_age_gate() {
        // Datable must not mean owned: a half-written record can never be read.
        val fresh = interruptedWrite(ageMs = 100)

        assertFalse(CrashRetention.isOwned(fresh.name, policy = policy))
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(listOf(fresh), nowMs = now, policy = policy))
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(listOf(fresh), nowMs = now, keepNames = emptySet(), policy = policy))
        assertEquals(
            emptyList(),
            CrashRetention.selectUnrecognized(listOf(fresh), nowMs = now, minAgeMillis = 5_000, policy = policy),
        )
    }

    @Test
    fun effectiveWriteTimeMs_dates_a_record_under_the_caller_s_own_policy() {
        // Expiry and the platform read gate must date a custom-suffix record the same way.
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

        val selected = CrashRetention.selectUnrecognized(entries, nowMs = now, minAgeMillis = 5_000, policy = policy)

        assertEquals(listOf("stale-legacy", "stale.tmp"), selected.map { it.name })
    }

    @Test
    fun selectUnrecognized_is_empty_when_only_owned_records_exist() {
        val entries = listOf(owned("123-abc.otlp", ageMs = 60_000))

        assertEquals(emptyList(), CrashRetention.selectUnrecognized(entries, now, minAgeMillis = 0, policy = policy))
    }

    @Test
    fun selectUnrecognized_dates_a_legacy_bare_millis_name_from_the_name_itself() {
        // Ownership is by suffix, so a parsed time cannot promote a legacy file to owned.
        val entries =
            listOf(
                CrashDirEntry("${now - 60_000}", lastModifiedMs = null, lengthBytes = 1),
                CrashDirEntry("${now - 100}", lastModifiedMs = null, lengthBytes = 1),
            )

        val selected = CrashRetention.selectUnrecognized(entries, nowMs = now, minAgeMillis = 5_000, policy = policy)

        assertEquals(listOf("${now - 60_000}"), selected.map { it.name })
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy))
    }

    @Test
    fun selectUnrecognized_leaves_an_undatable_foreign_file_alone() {
        // The age gate protects another writer's in-flight file; an entry it cannot measure has not cleared it.
        val entries = listOf(undatable("stale.tmp"))

        assertEquals(emptyList(), CrashRetention.selectUnrecognized(entries, nowMs = now, minAgeMillis = 5_000, policy = policy))
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

        val expired = CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy)

        assertEquals(listOf("1-a.otlp"), expired.map { it.name })
    }

    @Test
    fun selectExpiredOwned_treats_a_record_at_exactly_the_ceiling_as_readable() {
        val entries = listOf(owned("1-a.otlp", ageMs = policy.maxReadAgeMillis))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy))
    }

    @Test
    fun selectExpiredOwned_ignores_a_plausible_backwards_clock_step() {
        // Negative age: the clock moved back an hour since the record was written.
        val entries = listOf(owned("1-a.otlp", ageMs = -60L * 60 * 1000))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy))
    }

    @Test
    fun selectExpiredOwned_reclaims_a_record_dated_past_the_window_into_the_future() {
        // The read gate (now - mtime >= minAge) can never pass, so only expiry will ever remove it.
        val entries = listOf(owned("1-a.otlp", ageMs = -(policy.maxReadAgeMillis + 1)))

        assertEquals(listOf("1-a.otlp"), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy).map { it.name })
    }

    @Test
    fun selectExpiredOwned_leaves_a_record_exactly_one_window_into_the_future() {
        // The boundary belongs to the backwards-clock case, matching the past-side ceiling.
        val entries = listOf(owned("1-a.otlp", ageMs = -policy.maxReadAgeMillis))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy))
    }

    @Test
    fun selectExpiredOwned_is_empty_for_an_empty_directory() {
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(emptyList(), nowMs = now, policy = policy))
    }

    @Test
    fun selectExpiredOwned_retains_a_recent_record_whose_timestamp_is_unreadable() {
        // The data-loss case: a minute-old crash listed with unreadable attributes after a reboot.
        val entries =
            listOf(
                undatedFile(ageMs = 60_000, tag = "nil"),
                CrashDirEntry("${now - 60_000}-zero.otlp", lastModifiedMs = 0, lengthBytes = 1),
            )

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy))
    }

    @Test
    fun selectExpiredOwned_still_expires_a_stale_record_whose_timestamp_is_unreadable() {
        val entries = listOf(undatedFile(ageMs = policy.maxReadAgeMillis + 1))

        assertEquals(listOf(entries[0].name), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy).map { it.name })
    }

    @Test
    fun selectExpiredOwned_never_expires_a_record_it_cannot_date_at_all() {
        // A failed read is not evidence of age, so the caps handle this instead.
        val entries = listOf(undatable("crash-report.otlp"))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy))
    }

    @Test
    fun selectExpiredOwned_writes_off_a_name_dated_unrecoverably_into_the_future() {
        val entries = listOf(undatedFile(ageMs = -(policy.maxReadAgeMillis + 1)))

        assertEquals(listOf(entries[0].name), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy).map { it.name })
    }

    @Test
    fun selectExpiredOwned_tolerates_a_name_dated_modestly_into_the_future() {
        val entries = listOf(undatedFile(ageMs = -60_000))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now, policy = policy))
    }

    // ===== accumulation =====

    @Test
    fun selectOverflowOwned_returns_nothing_while_within_both_caps() {
        val entries = (1..3).map { owned("$it-a.otlp", ageMs = it * 1_000L) }

        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = emptySet(), policy = policy))
    }

    @Test
    fun selectOverflowOwned_evicts_oldest_first_past_the_count_cap() {
        val max = policy.maxRecordCount
        val entries = (1..max + 2).map { owned("$it-a.otlp", ageMs = it * 1_000L) }

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = emptySet(), policy = policy)

        // Larger index means older, and the result is ordered oldest-first.
        assertEquals(listOf("${max + 2}-a.otlp", "${max + 1}-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_never_touches_foreign_entries() {
        val max = policy.maxRecordCount
        val entries =
            (1..max + 1).map { owned("$it-a.otlp", ageMs = it * 1_000L) } +
                foreign("legacy", ageMs = 999_000)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = emptySet(), policy = policy)

        assertFalse(evicted.any { it.name == "legacy" })
    }

    @Test
    fun an_oversized_record_is_retained_and_cannot_displace_the_rest() {
        // Size only caps budget claim; it is never grounds for eviction.
        val entries =
            listOf(
                owned("5-newest.otlp", ageMs = 1_000, bytes = 10),
                owned("4-huge.otlp", ageMs = 2_000, bytes = policy.maxTotalBytes * 2),
                owned("3-small.otlp", ageMs = 3_000, bytes = 10),
                owned("2-small.otlp", ageMs = 4_000, bytes = 10),
            )

        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = emptySet(), policy = policy))
    }

    @Test
    fun a_record_that_does_not_fit_the_remaining_budget_is_skipped_not_treated_as_a_cutoff() {
        // Four near-cap records fill most of the budget; the next cannot fit but a smaller, older one can.
        val nearCap = policy.maxRecordBytes - 12_288
        val entries =
            (1..4).map { owned("${10 - it}-fills.otlp", ageMs = it * 1_000L, bytes = nearCap) } +
                owned("5-does-not-fit.otlp", ageMs = 5_000, bytes = 200_000) +
                owned("4-still-fits.otlp", ageMs = 6_000, bytes = 40_000)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = emptySet(), policy = policy)

        assertEquals(listOf("5-does-not-fit.otlp"), evicted.map { it.name })
    }

    @Test
    fun keepNames_retains_the_just_written_record_even_when_it_sorts_oldest() {
        val max = policy.maxRecordCount
        val entries =
            (1..max).map { owned("$it-a.otlp", ageMs = it * 1_000L) } +
                owned("fresh-a.otlp", ageMs = 999_000)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = setOf("fresh-a.otlp"), policy = policy)

        assertFalse(evicted.any { it.name == "fresh-a.otlp" })
        assertEquals(listOf("$max-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun an_oversized_protected_record_does_not_evict_the_pending_backlog() {
        val backlog = (1..4).map { owned("$it-small.otlp", ageMs = it * 10_000L, bytes = 400_000) }
        val entries =
            backlog + owned("fresh-a.otlp", ageMs = 1_000, bytes = policy.maxTotalBytes * 2)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = setOf("fresh-a.otlp"), policy = policy)

        assertEquals(emptyList(), evicted)
    }

    @Test
    fun equal_timestamps_break_the_tie_on_the_millis_embedded_in_the_name() {
        val entries =
            listOf(
                CrashDirEntry("100-a.otlp", lastModifiedMs = now, lengthBytes = 10),
                CrashDirEntry("300-c.otlp", lastModifiedMs = now, lengthBytes = 10),
                CrashDirEntry("200-b.otlp", lastModifiedMs = now, lengthBytes = 10),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2), keepNames = emptySet())

        assertEquals(listOf("100-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun a_future_dated_record_is_evicted_before_any_record_that_could_still_upload() {
        // The write path enforces caps without running expiry, so ranking has to make this call on its own.
        val entries =
            listOf(
                owned("9-zombie.otlp", ageMs = -(policy.maxReadAgeMillis + 1)),
                owned("300-a.otlp", ageMs = 1_000),
                owned("200-b.otlp", ageMs = 2_000),
                owned("100-c.otlp", ageMs = 3_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2), keepNames = emptySet())

        assertEquals(listOf("9-zombie.otlp", "100-c.otlp"), evicted.map { it.name })
    }

    @Test
    fun a_modestly_future_record_still_ranks_among_the_newest() {
        val entries =
            listOf(
                owned("9-clock-skew.otlp", ageMs = -60_000),
                owned("300-a.otlp", ageMs = 1_000),
                owned("100-c.otlp", ageMs = 3_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2), keepNames = emptySet())

        assertEquals(listOf("100-c.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_is_empty_for_an_empty_directory() {
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(emptyList(), nowMs = now, keepNames = emptySet(), policy = policy))
    }

    // ===== accumulation: records whose timestamp is unreadable =====

    @Test
    fun selectOverflowOwned_ranks_an_undated_record_by_the_millis_in_its_name() {
        val entries =
            listOf(
                owned("300-a.otlp", ageMs = 5_000),
                undatedFile(ageMs = 1_000, tag = "undated"),
                owned("100-c.otlp", ageMs = 10_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2), keepNames = emptySet())

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
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2), keepNames = emptySet())

        assertEquals(listOf(zombie.name, "100-c.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_does_not_privilege_the_more_absurdly_future_name() {
        // Both sort as "now" once capped, so listing order decides rather than which name is further ahead.
        val near = undatedFile(ageMs = -10_000, tag = "near")
        val far = undatedFile(ageMs = -200_000, tag = "far")

        val evicted =
            CrashRetention.selectOverflowOwned(
                listOf(near, far),
                nowMs = now,
                policy = policy.copy(maxRecordCount = 1),
                keepNames = emptySet(),
            )

        assertEquals(listOf(far.name), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_evicts_an_undatable_record_before_one_it_can_date() {
        val entries =
            listOf(
                undatable("crash-x.otlp"),
                owned("300-a.otlp", ageMs = 1_000),
                owned("100-c.otlp", ageMs = 3_000),
            )

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2), keepNames = emptySet())

        assertEquals(listOf("crash-x.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_still_evicts_records_it_cannot_date() {
        // An entry left out of the caps is outside every bound at once and leaks for the life of the install.
        val entries = ('a'..'d').map { undatable("crash-$it.otlp") }

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 2), keepNames = emptySet())

        assertEquals(2, evicted.size)
    }

    @Test
    fun selectOverflowOwned_trims_the_oldest_when_no_timestamp_is_readable() {
        // The 5s and 4s entries are the two oldest: the millis in the names still order them.
        val ages = listOf(3_000L, 1_000L, 5_000L, 2_000L, 4_000L)
        val entries = ages.mapIndexed { index, ageMs -> undatedFile(ageMs = ageMs, tag = "r$index") }

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 3), keepNames = emptySet())

        assertEquals(listOf(entries[2].name, entries[4].name), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_trims_only_the_excess_when_nothing_can_be_dated() {
        // The degenerate case: only the overflow goes, in listing order, not the whole directory.
        val entries = ('a'..'e').map { undatable("crash-$it.otlp") }

        val evicted =
            CrashRetention.selectOverflowOwned(entries, nowMs = now, policy = policy.copy(maxRecordCount = 3), keepNames = emptySet())

        assertEquals(listOf("crash-e.otlp", "crash-d.otlp"), evicted.map { it.name })
    }

    // ===== accumulation: protected names =====

    @Test
    fun keepNames_protects_every_name_in_the_set_at_once() {
        // Concurrent crashing threads each hold a record open.
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
                policy = policy,
            )

        assertEquals(listOf("$max-a.otlp", "${max - 1}-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun keepNames_are_retained_even_when_the_set_alone_exceeds_the_caps() {
        // The overshoot is deliberate and ends when those writes finish and leave the set.
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
        // Four in-flight writes at the per-record ceiling claim exactly maxTotalBytes between them.
        val inFlight =
            (1..4).map { owned("flight-$it.otlp", ageMs = it * 1_000L, bytes = policy.maxRecordBytes) }
        val backlog = (1..6).map { owned("$it-pending.otlp", ageMs = it * 100_000L, bytes = 1_000) }

        val evicted =
            CrashRetention.selectOverflowOwned(
                inFlight + backlog,
                nowMs = now,
                keepNames = inFlight.mapTo(HashSet()) { it.name },
                policy = policy,
            )

        assertEquals(emptyList(), evicted)
    }

    @Test
    fun the_unprotected_pass_keeps_its_own_byte_budget_while_names_are_protected() {
        // Protected claims are excused, not the budget itself.
        val inFlight =
            (1..4).map { owned("flight-$it.otlp", ageMs = it * 1_000L, bytes = policy.maxRecordBytes) }
        val backlog =
            (1..5).map { owned("$it-pending.otlp", ageMs = it * 100_000L, bytes = policy.maxRecordBytes) }

        val evicted =
            CrashRetention.selectOverflowOwned(
                inFlight + backlog,
                nowMs = now,
                keepNames = inFlight.mapTo(HashSet()) { it.name },
                policy = policy,
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

        val expiredNames = CrashRetention.selectExpiredOwned(listing, nowMs = now, policy = policy).map { it.name }.toSet()
        val survivors = listing.filterNot { it.name in expiredNames }

        assertEquals(policy.maxRecordCount, expiredNames.size)
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(survivors, nowMs = now, keepNames = emptySet(), policy = policy))
    }

    @Test
    fun overflow_alone_sheds_an_expired_record_before_one_it_cannot_date() {
        // A record past the read ceiling is known unuploadable; an undatable one may still be a live crash.
        val listing =
            (1..49).map { owned("fresh-$it.otlp", ageMs = it * 1_000L) } +
                owned("stale.otlp", ageMs = policy.maxReadAgeMillis + 60_000) +
                undatable("crash-x.otlp")

        val evicted = CrashRetention.selectOverflowOwned(listing, nowMs = now, keepNames = emptySet(), policy = policy)

        assertEquals(listOf("stale.otlp"), evicted.map { it.name })

        // And that is exactly what running expiry first would have left.
        val expiredNames = CrashRetention.selectExpiredOwned(listing, nowMs = now, policy = policy).map { it.name }.toSet()
        assertEquals(setOf("stale.otlp"), expiredNames)
        assertEquals(
            emptyList(),
            CrashRetention.selectOverflowOwned(listing.filterNot { it.name in expiredNames }, nowMs = now, keepNames = emptySet(), policy = policy),
        )
    }

    @Test
    fun ordering_the_two_passes_does_not_change_which_live_records_survive() {
        val expired =
            (1..100).map { owned("expired-$it.otlp", ageMs = policy.maxReadAgeMillis + it * 1_000L) }
        val fresh = (1..60).map { owned("fresh-$it.otlp", ageMs = it * 1_000L) }
        val listing = expired + fresh

        val fromRaw = CrashRetention.selectOverflowOwned(listing, nowMs = now, keepNames = emptySet(), policy = policy)
        val expiredNames = CrashRetention.selectExpiredOwned(listing, nowMs = now, policy = policy).map { it.name }.toSet()
        val fromSurvivors =
            CrashRetention.selectOverflowOwned(listing.filterNot { it.name in expiredNames }, nowMs = now, keepNames = emptySet(), policy = policy)

        val liveFromRaw = fromRaw.filter { it.name.startsWith("fresh-") }
        val liveFromSurvivors = fromSurvivors.filter { it.name.startsWith("fresh-") }

        // 60 live against a cap of 50: the same 10 go either way.
        assertEquals(10, liveFromRaw.size)
        assertEquals(liveFromRaw.map { it.name }, liveFromSurvivors.map { it.name })
    }

    @Test
    fun overflow_alone_leaves_an_expired_record_that_fits_under_the_caps() {
        // Why both passes are required: being over-age reclaims nothing while the caps are satisfied.
        val listing = listOf(owned("stale.otlp", ageMs = policy.maxReadAgeMillis + 60_000))

        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(listing, nowMs = now, keepNames = emptySet(), policy = policy))
        assertEquals(listOf("stale.otlp"), CrashRetention.selectExpiredOwned(listing, nowMs = now, policy = policy).map { it.name })
    }

    // ===== cheap-path agreement =====

    @Test
    fun isWithinCaps_agrees_with_the_selector() {
        val within = (1..3).map { owned("$it-a.otlp", ageMs = it * 1_000L) }
        assertTrue(CrashRetention.isWithinCaps(within, keepNames = emptySet(), policy = policy))
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(within, nowMs = now, keepNames = emptySet(), policy = policy))

        val overCount = (1..policy.maxRecordCount + 1).map { owned("$it-a.otlp", ageMs = it * 1_000L) }
        assertFalse(CrashRetention.isWithinCaps(overCount, keepNames = emptySet(), policy = policy))
        assertTrue(CrashRetention.selectOverflowOwned(overCount, nowMs = now, keepNames = emptySet(), policy = policy).isNotEmpty())
    }

    @Test
    fun isWithinCaps_reports_over_budget_while_the_count_is_still_within_cap() {
        // Five records at the per-record ceiling claim 2.5 MiB against a 2 MiB budget.
        val entries =
            (1..5).map { owned("$it-a.otlp", ageMs = it * 1_000L, bytes = policy.maxRecordBytes) }

        assertTrue(entries.size <= policy.maxRecordCount)
        assertFalse(CrashRetention.isWithinCaps(entries, keepNames = emptySet(), policy = policy))
        assertTrue(CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = emptySet(), policy = policy).isNotEmpty())
    }

    @Test
    fun isWithinCaps_counts_records_it_cannot_date() {
        val entries = (1..policy.maxRecordCount + 1).map { undatable("crash-$it.otlp") }

        assertFalse(CrashRetention.isWithinCaps(entries, keepNames = emptySet(), policy = policy))
        assertTrue(CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = emptySet(), policy = policy).isNotEmpty())
    }

    @Test
    fun isWithinCaps_excuses_protected_claims_exactly_as_the_selector_does() {
        // Five records at 500 KiB against a 2 MiB budget, newest in flight. Disagreement here costs the
        // crashing thread a full directory sort that then deletes nothing.
        val entries =
            (1..5).map { owned("$it-a.otlp", ageMs = it * 1_000L, bytes = 500L * 1024) }
        val inFlight = setOf("1-a.otlp")

        assertTrue(CrashRetention.isWithinCaps(entries, inFlight, policy = policy))
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = inFlight, policy = policy))

        // The control: excusing protected claims is not excusing the budget.
        assertFalse(CrashRetention.isWithinCaps(entries, keepNames = emptySet(), policy = policy))
        assertTrue(CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = emptySet(), policy = policy).isNotEmpty())
    }

    @Test
    fun isWithinCaps_counts_protected_records_against_the_record_cap() {
        // They claim no bytes but they do hold count slots.
        val entries = (1..policy.maxRecordCount + 1).map { owned("$it-a.otlp", ageMs = it * 1_000L) }
        val inFlight = setOf("1-a.otlp")

        assertFalse(CrashRetention.isWithinCaps(entries, inFlight, policy = policy))
        assertTrue(CrashRetention.selectOverflowOwned(entries, nowMs = now, keepNames = inFlight, policy = policy).isNotEmpty())
    }

    @Test
    fun isWithinCaps_charges_oversized_records_only_their_capped_share() {
        // Otherwise one huge inherited record makes the crash path sort and trim on every write.
        val entries = listOf(owned("huge.otlp", ageMs = 1_000, bytes = policy.maxTotalBytes * 4))

        assertTrue(CrashRetention.isWithinCaps(entries, keepNames = emptySet(), policy = policy))
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
                policy = policy,
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
                policy = policy,
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
                policy = policy,
            )

        assertTrue(line.contains("total=1 otlp=1 legacy=0"), line)
        assertTrue(line.contains("…(+1 more)"), line)
    }

    @Test
    fun formatInventory_reports_an_age_it_cannot_compute_as_unknown() {
        // A fabricated age would make live records look ancient in the logs used to verify retention.
        val line =
            CrashRetention.formatInventory(
                label = "before-upload",
                path = "/cache/crashes",
                entries = listOf(undatedFile(ageMs = 60_000), undatable("crash-x.otlp")),
                nowMs = now,
                maxSample = 2,
                policy = policy,
            )

        assertTrue(line.contains("ageMs=60000"), line)
        assertTrue(line.contains("name=crash-x.otlp bytes=1 ageMs=unknown"), line)
    }
}
