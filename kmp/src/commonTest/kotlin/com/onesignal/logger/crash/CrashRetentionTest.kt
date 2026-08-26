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
    private val policy = CrashRetention.defaultPolicy

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
    fun keepName_retains_the_just_written_record_even_when_it_sorts_oldest() {
        val max = policy.maxRecordCount
        val entries =
            (1..max).map { owned("$it-a.otlp", ageMs = it * 1_000L) } +
                owned("fresh-a.otlp", ageMs = 999_000)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepName = "fresh-a.otlp")

        assertFalse(evicted.any { it.name == "fresh-a.otlp" })
        assertEquals(listOf("$max-a.otlp"), evicted.map { it.name })
    }

    @Test
    fun an_oversized_keepName_does_not_evict_the_pending_backlog() {
        // Charging keepName its full length started the budget over cap, so every sibling
        // failed the remaining-budget check and the whole backlog was evicted.
        val backlog = (1..4).map { owned("$it-small.otlp", ageMs = it * 10_000L, bytes = 400_000) }
        val entries =
            backlog + owned("fresh-a.otlp", ageMs = 1_000, bytes = policy.maxTotalBytes * 2)

        val evicted = CrashRetention.selectOverflowOwned(entries, nowMs = now, keepName = "fresh-a.otlp")

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

    // ===== composition: expiry must run before overflow =====

    @Test
    fun overflow_fed_the_expiry_survivors_reclaims_only_the_expired_records() {
        // The order the ILogFileStore contract requires. Expired records are still on disk
        // when the listing is taken, so if they are passed to the overflow pass they consume
        // count slots and evict live, uploadable records to make room for records that are
        // about to be deleted anyway.
        val expired =
            (1..policy.maxRecordCount).map {
                owned("expired-$it.otlp", ageMs = policy.maxReadAgeMillis + it * 1_000L)
            }
        val fresh = (1..5).map { owned("fresh-$it.otlp", ageMs = it * 1_000L) }
        val listing = expired + fresh

        val expiredNames = CrashRetention.selectExpiredOwned(listing, nowMs = now).map { it.name }.toSet()
        val survivors = listing.filterNot { it.name in expiredNames }
        val evicted = CrashRetention.selectOverflowOwned(survivors, nowMs = now)

        assertEquals(policy.maxRecordCount, expiredNames.size)
        // Every fresh record survives; nothing beyond the expired set is reclaimed.
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun overflow_fed_the_raw_listing_would_evict_live_records() {
        // Pins why the order matters, so the contract note has a failing case behind it.
        val expired =
            (1..policy.maxRecordCount).map {
                owned("expired-$it.otlp", ageMs = policy.maxReadAgeMillis + it * 1_000L)
            }
        val fresh = (1..5).map { owned("fresh-$it.otlp", ageMs = it * 1_000L) }

        val evicted = CrashRetention.selectOverflowOwned(expired + fresh, nowMs = now)

        // 55 owned records against a cap of 50 — the 5 oldest go, and they are all expired
        // here, but the count slots they occupied are what forces any eviction at all.
        assertEquals(5, evicted.size)
        assertTrue(evicted.all { it.name.startsWith("expired-") }, evicted.map { it.name }.toString())
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
}
