package com.onesignal.logger.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Retention is pure decision logic, so it is exercised here with fixed timestamps and no
 * filesystem. Platform stores are then only responsible for turning a directory listing into
 * [CrashDirEntry]s and applying the returned decisions.
 */
class CrashRetentionTest {
    private val now = 1_000_000L

    private fun owned(name: String, ageMs: Long, bytes: Long = 1L) =
        CrashDirEntry(name, lastModifiedMs = now - ageMs, lengthBytes = bytes)

    private fun foreign(name: String, ageMs: Long, bytes: Long = 1L) =
        CrashDirEntry(name, lastModifiedMs = now - ageMs, lengthBytes = bytes)

    // ===== ownership =====

    @Test
    fun isOwned_recognizes_only_the_owned_suffix() {
        assertTrue(CrashRetention.isOwned("123-abc.otlp"))
        assertFalse(CrashRetention.isOwned("1784621689841"))
        assertFalse(CrashRetention.isOwned("stale.tmp"))
        assertFalse(CrashRetention.isOwned("123-abc.otlp.tmp"))
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

    // ===== expiry =====

    @Test
    fun selectExpiredOwned_takes_only_owned_records_strictly_past_the_ceiling() {
        val entries =
            listOf(
                owned("1-a.otlp", ageMs = CrashRetention.MAX_READ_AGE_MILLIS + 1),
                owned("2-b.otlp", ageMs = CrashRetention.MAX_READ_AGE_MILLIS - 1),
                foreign("legacy", ageMs = CrashRetention.MAX_READ_AGE_MILLIS * 2),
            )

        val expired = CrashRetention.selectExpiredOwned(entries, nowMs = now)

        assertEquals(listOf("1-a.otlp"), expired.map { it.name })
    }

    @Test
    fun selectExpiredOwned_treats_a_record_at_exactly_the_ceiling_as_readable() {
        val entries = listOf(owned("1-a.otlp", ageMs = CrashRetention.MAX_READ_AGE_MILLIS))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    @Test
    fun selectExpiredOwned_ignores_records_whose_write_time_is_in_the_future() {
        // A backwards clock step must not read as extreme age in either direction.
        val entries = listOf(owned("1-a.otlp", ageMs = -CrashRetention.MAX_READ_AGE_MILLIS * 2))

        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(entries, nowMs = now))
    }

    @Test
    fun selectExpiredOwned_is_empty_for_an_empty_directory() {
        assertEquals(emptyList(), CrashRetention.selectExpiredOwned(emptyList(), nowMs = now))
    }

    // ===== accumulation =====

    @Test
    fun selectOverflowOwned_returns_nothing_while_within_both_caps() {
        val entries = (1..3).map { owned("$it-a.otlp", ageMs = it * 1_000L) }

        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(entries))
    }

    @Test
    fun selectOverflowOwned_evicts_oldest_first_past_the_count_cap() {
        val max = CrashRetention.MAX_RECORD_COUNT
        val entries = (1..max + 2).map { owned("$it-a.otlp", ageMs = it * 1_000L) }

        val evicted = CrashRetention.selectOverflowOwned(entries)

        // Larger index means older, and the result is ordered oldest-first.
        assertEquals(listOf("${max + 2}-a.otlp", "${max + 1}-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_never_touches_foreign_entries() {
        val max = CrashRetention.MAX_RECORD_COUNT
        val entries =
            (1..max + 1).map { owned("$it-a.otlp", ageMs = it * 1_000L) } +
                foreign("legacy", ageMs = 999_000)

        val evicted = CrashRetention.selectOverflowOwned(entries)

        assertFalse(evicted.any { it.name == "legacy" })
    }

    @Test
    fun an_oversized_record_is_retained_and_cannot_displace_the_rest() {
        // Size alone is never grounds for eviction: deleting a captured crash without ever
        // attempting to upload it is worse than keeping it. Size only caps budget claim.
        val entries =
            listOf(
                owned("5-newest.otlp", ageMs = 1_000, bytes = 10),
                owned("4-huge.otlp", ageMs = 2_000, bytes = CrashRetention.MAX_TOTAL_BYTES * 2),
                owned("3-small.otlp", ageMs = 3_000, bytes = 10),
                owned("2-small.otlp", ageMs = 4_000, bytes = 10),
            )

        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(entries))
    }

    @Test
    fun a_record_that_does_not_fit_the_remaining_budget_is_skipped_not_treated_as_a_cutoff() {
        // Four near-cap records fill most of the budget. The next cannot fit, but a smaller
        // and *older* one still can — proving the loop skips rather than stopping.
        val nearCap = CrashRetention.MAX_RECORD_BYTES - 12_288
        val entries =
            (1..4).map { owned("${10 - it}-fills.otlp", ageMs = it * 1_000L, bytes = nearCap) } +
                owned("5-does-not-fit.otlp", ageMs = 5_000, bytes = 200_000) +
                owned("4-still-fits.otlp", ageMs = 6_000, bytes = 40_000)

        val evicted = CrashRetention.selectOverflowOwned(entries)

        assertEquals(listOf("5-does-not-fit.otlp"), evicted.map { it.name })
    }

    @Test
    fun keepName_retains_the_just_written_record_even_when_it_sorts_oldest() {
        val max = CrashRetention.MAX_RECORD_COUNT
        val entries =
            (1..max).map { owned("$it-a.otlp", ageMs = it * 1_000L) } +
                owned("fresh-a.otlp", ageMs = 999_000)

        val evicted = CrashRetention.selectOverflowOwned(entries, keepName = "fresh-a.otlp")

        assertFalse(evicted.any { it.name == "fresh-a.otlp" })
        assertEquals(listOf("$max-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun an_oversized_keepName_does_not_evict_the_pending_backlog() {
        // Charging keepName its full length started the budget over cap, so every sibling
        // failed the remaining-budget check and the whole backlog was evicted.
        val backlog = (1..4).map { owned("$it-small.otlp", ageMs = it * 10_000L, bytes = 400_000) }
        val entries =
            backlog + owned("fresh-a.otlp", ageMs = 1_000, bytes = CrashRetention.MAX_TOTAL_BYTES * 2)

        val evicted = CrashRetention.selectOverflowOwned(entries, keepName = "fresh-a.otlp")

        assertFalse(evicted.any { it.name == "fresh-a.otlp" })
        assertEquals(listOf("4-small.otlp"), evicted.map { it.name })
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

        val evicted = CrashRetention.selectOverflowOwned(entries, maxCount = 2)

        assertEquals(listOf("100-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun selectOverflowOwned_is_empty_for_an_empty_directory() {
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(emptyList()))
    }

    // ===== cheap-path agreement =====

    @Test
    fun isWithinCaps_agrees_with_the_selector() {
        val within = (1..3).map { owned("$it-a.otlp", ageMs = it * 1_000L) }
        assertTrue(CrashRetention.isWithinCaps(within))
        assertEquals(emptyList(), CrashRetention.selectOverflowOwned(within))

        val overCount = (1..CrashRetention.MAX_RECORD_COUNT + 1).map { owned("$it-a.otlp", ageMs = it * 1_000L) }
        assertFalse(CrashRetention.isWithinCaps(overCount))
        assertTrue(CrashRetention.selectOverflowOwned(overCount).isNotEmpty())
    }

    @Test
    fun isWithinCaps_charges_oversized_records_only_their_capped_share() {
        // One huge inherited record must not make the directory look over budget on its own,
        // or a crash path would sort and trim on every single write.
        val entries = listOf(owned("huge.otlp", ageMs = 1_000, bytes = CrashRetention.MAX_TOTAL_BYTES * 4))

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
}
