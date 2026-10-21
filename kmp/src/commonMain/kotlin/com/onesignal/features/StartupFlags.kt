package com.onesignal.features

/** Where a process's [StartupFlags] came from. Anything but [STORE] means every startup flag is off. */
enum class StartupFlagsSource {
    STORE,
    MISSING,
    UNREADABLE,
    NO_STORE,
}

/** The [FeatureActivationMode.APP_STARTUP] flags as one process latched them. */
class StartupFlags(
    val source: StartupFlagsSource,
    val keys: Set<String>,
) {
    /** Only APP_STARTUP flags: IMMEDIATE flags change mid-run, so a startup copy would go stale. */
    @Throws(IllegalArgumentException::class)
    fun isEnabled(flag: FeatureFlag): Boolean {
        require(flag.activationMode == FeatureActivationMode.APP_STARTUP) { "${flag.key} is not APP_STARTUP" }
        return flag.key in keys
    }
}

/**
 * Platform storage for the startup flags: a small store the host can read before SDK init without
 * loading its main persisted state. Android uses its own SharedPreferences file.
 */
interface IStartupFlagsStore {
    /** Keys from the last [write], or null when nothing was ever written. May throw when unreadable. */
    fun read(): Set<String>?

    /** Must be durable when it returns: the next process reads it at startup. */
    fun write(keys: Set<String>)
}

/** Canonical keys of the APP_STARTUP catalog flags present in [remoteKeys]. */
fun appStartupKeysIn(remoteKeys: Collection<String>): Set<String> {
    val canonical = remoteKeys.mapTo(HashSet()) { canonicalizeFeatureFlagId(it) }
    return FeatureFlag.entries
        .filter { it.activationMode == FeatureActivationMode.APP_STARTUP && it.key in canonical }
        .mapTo(LinkedHashSet()) { it.key }
}

/**
 * Reads the startup flags once per process and keeps the store in step with the remote flags.
 * Hosts keep one instance for the process. The first [snapshot] wins, so code that runs before
 * init and [FeatureManager.refreshAtStartup] latch the same values.
 */
class StartupFlagsLatch {
    private val lock = PlatformLock()
    private var snapshot: StartupFlags? = null

    /** [store] is only called on the first read; null means the host has no store yet. */
    fun snapshot(store: () -> IStartupFlagsStore?): StartupFlags =
        lock.withLock {
            snapshot ?: read(store()).also { snapshot = it }
        }

    /** Writes the APP_STARTUP subset of [remoteKeys] for the next process. Returns false when unchanged. */
    fun mirror(
        store: IStartupFlagsStore,
        remoteKeys: Collection<String>,
    ): Boolean {
        val wanted = appStartupKeysIn(remoteKeys)
        if (store.read() == wanted) return false
        store.write(wanted)
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun read(store: IStartupFlagsStore?): StartupFlags {
        if (store == null) return StartupFlags(StartupFlagsSource.NO_STORE, emptySet())
        val stored =
            try {
                store.read()
            } catch (t: Throwable) {
                return StartupFlags(StartupFlagsSource.UNREADABLE, emptySet())
            } ?: return StartupFlags(StartupFlagsSource.MISSING, emptySet())
        return StartupFlags(StartupFlagsSource.STORE, appStartupKeysIn(stored))
    }
}
