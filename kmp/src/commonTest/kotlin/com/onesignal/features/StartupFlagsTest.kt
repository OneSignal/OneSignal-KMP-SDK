package com.onesignal.features

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeStartupFlagsStore(
    var stored: Set<String>? = null,
    var failReads: Boolean = false,
    var readFailure: () -> Throwable = { IllegalStateException("locked") },
) : IStartupFlagsStore {
    var reads = 0
    var writes = 0

    override fun read(): Set<String>? {
        reads++
        if (failReads) throw readFailure()
        return stored
    }

    override fun write(keys: Set<String>) {
        writes++
        stored = keys
    }
}

class StartupFlagsTest {
    private val startup = FeatureFlag.SDK_CUSTOM_LOGGING
    private val immediate = FeatureFlag.SDK_IDENTITY_VERIFICATION

    @Test
    fun appStartupKeysKeepsOnlyStartupCatalogFlagsInCanonicalCase() {
        val keys = appStartupKeysIn(listOf("SDK_Custom_Logging", immediate.key, "not_in_catalog"))
        assertEquals(setOf(startup.key), keys)
    }

    @Test
    fun snapshotReadsTheStoreOnce() {
        val store = FakeStartupFlagsStore(setOf(startup.key))
        val latch = StartupFlagsLatch()

        assertTrue(latch.snapshot { store }.isEnabled(startup))
        store.stored = emptySet()

        assertTrue(latch.snapshot { store }.isEnabled(startup))
        assertEquals(1, store.reads)
    }

    @Test
    fun snapshotSourcesAreAllOffExceptStore() {
        assertEquals(StartupFlagsSource.MISSING, StartupFlagsLatch().snapshot { FakeStartupFlagsStore() }.source)
        assertEquals(StartupFlagsSource.NO_STORE, StartupFlagsLatch().snapshot { null }.source)
        val unreadable = StartupFlagsLatch().snapshot { FakeStartupFlagsStore(setOf(startup.key), failReads = true) }
        assertEquals(StartupFlagsSource.UNREADABLE, unreadable.source)
        assertFalse(unreadable.isEnabled(startup))
    }

    @Test
    fun snapshotDropsKeysThatAreNoLongerStartupFlags() {
        val flags = StartupFlagsLatch().snapshot { FakeStartupFlagsStore(setOf(startup.key, immediate.key, "retired")) }
        assertEquals(setOf(startup.key), flags.keys)
    }

    @Test
    fun isEnabledIsFalseForImmediateFlags() {
        val flags = StartupFlags(StartupFlagsSource.STORE, setOf(immediate.key))
        assertFalse(flags.isEnabled(immediate))
    }

    @Test
    fun aCancelledReadIsNotCachedAsUnreadable() {
        val store = FakeStartupFlagsStore(setOf(startup.key), failReads = true, readFailure = { CancellationException("cancelled") })
        val latch = StartupFlagsLatch()
        assertFailsWith<CancellationException> { latch.snapshot { store } }

        store.failReads = false
        assertTrue(latch.snapshot { store }.isEnabled(startup))
    }

    @Test
    fun mirrorWritesWhenTheStoreIsUnreadable() {
        val store = FakeStartupFlagsStore(failReads = true)

        assertTrue(StartupFlagsLatch().mirror(store, listOf(startup.key)))

        assertEquals(setOf(startup.key), store.stored)
        store.failReads = false
        assertTrue(StartupFlagsLatch().snapshot { store }.isEnabled(startup))
    }

    @Test
    fun refreshAtStartupLatchesTheSnapshotEvenAfterAnEarlierRefresh() {
        val manager = FeatureManager()
        manager.refresh(listOf(startup.key), applyAppStartupFlags = true)
        assertTrue(manager.isEnabled(startup))

        manager.refreshAtStartup(listOf(startup.key), StartupFlags(StartupFlagsSource.STORE, emptySet()))

        assertFalse(manager.isEnabled(startup))
    }

    @Test
    fun refreshAtStartupReportsASnapshotThatDisagreesWithRemote() {
        val manager = FeatureManager()

        val deferred = manager.refreshAtStartup(listOf(startup.key), StartupFlags(StartupFlagsSource.MISSING, emptySet()))

        assertEquals(listOf(DeferredFeatureActivation(startup.key, desiredEnabled = true, latchedEnabled = false)), deferred)
    }

    @Test
    fun onlyAStoreSourceCanReportAFlagOn() {
        for (source in StartupFlagsSource.entries - StartupFlagsSource.STORE) {
            val flags = StartupFlags(source, setOf(startup.key))
            assertEquals(emptySet(), flags.keys, "$source")
            assertFalse(flags.isEnabled(startup), "$source")
        }
        assertTrue(StartupFlags(StartupFlagsSource.STORE, setOf(startup.key)).isEnabled(startup))
    }

    @Test
    fun storeKeysAreCanonical() {
        val flags = StartupFlags(StartupFlagsSource.STORE, setOf("SDK_Custom_Logging"))
        assertEquals(setOf(startup.key), flags.keys)
        assertTrue(flags.isEnabled(startup))
    }

    @Test
    fun refreshAtStartupIgnoresKeysFromANonStoreSource() {
        val manager = FeatureManager()
        manager.refreshAtStartup(listOf(startup.key), StartupFlags(StartupFlagsSource.UNREADABLE, setOf(startup.key)))
        assertFalse(manager.isEnabled(startup))
    }

    @Test
    fun mirrorWritesOnlyWhenTheStartupSubsetChanges() {
        val store = FakeStartupFlagsStore()
        val latch = StartupFlagsLatch()

        assertTrue(latch.mirror(store, listOf(startup.key, immediate.key)))
        assertFalse(latch.mirror(store, listOf("SDK_CUSTOM_LOGGING")))
        assertFalse(latch.mirror(store, listOf(startup.key, "not_in_catalog")))

        assertEquals(setOf(startup.key), store.stored)
        assertEquals(1, store.writes)
    }

    @Test
    fun turningAFlagOffReachesTheNextProcess() {
        val store = FakeStartupFlagsStore()
        val firstProcess = StartupFlagsLatch()
        firstProcess.mirror(store, listOf(startup.key))
        assertTrue(StartupFlagsLatch().snapshot { store }.isEnabled(startup))

        val running = StartupFlagsLatch()
        assertTrue(running.snapshot { store }.isEnabled(startup))
        running.mirror(store, emptyList())
        assertTrue(running.snapshot { store }.isEnabled(startup))

        val nextProcess = StartupFlagsLatch().snapshot { store }
        assertEquals(StartupFlagsSource.STORE, nextProcess.source)
        assertFalse(nextProcess.isEnabled(startup))
    }

    @Test
    fun refreshAtStartupLatchesStartupFlagsFromTheSnapshotAndImmediateFromRemote() {
        val manager = FeatureManager()
        val snapshot = StartupFlags(StartupFlagsSource.STORE, emptySet())

        manager.refreshAtStartup(listOf(startup.key, immediate.key), snapshot)

        assertFalse(manager.isEnabled(startup))
        assertTrue(manager.isEnabled(immediate))
        assertEquals(listOf(immediate.key), manager.enabledFeatureKeys())
    }

    @Test
    fun laterRefreshesDeferStartupChangesAgainstTheSnapshot() {
        val manager = FeatureManager()
        manager.refreshAtStartup(emptyList(), StartupFlags(StartupFlagsSource.STORE, setOf(startup.key)))
        assertTrue(manager.isEnabled(startup))

        val deferred = manager.refresh(emptyList(), applyAppStartupFlags = false)

        assertTrue(manager.isEnabled(startup))
        assertEquals(listOf(DeferredFeatureActivation(startup.key, desiredEnabled = false, latchedEnabled = true)), deferred)
    }

    @Test
    fun aSecondStartupRefreshCannotLatchFromRemoteKeys() {
        val manager = FeatureManager()
        manager.refreshAtStartup(emptyList(), StartupFlags(StartupFlagsSource.MISSING, emptySet()))

        manager.refresh(listOf(startup.key), applyAppStartupFlags = true)

        assertFalse(manager.isEnabled(startup))
    }
}
