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
    keys: Set<String>,
) {
    /** Canonical keys, and always empty unless [source] is [StartupFlagsSource.STORE]. */
    val keys: Set<String> =
        if (source == StartupFlagsSource.STORE) keys.mapTo(LinkedHashSet()) { canonicalizeFeatureFlagId(it) } else emptySet()

    /** Only APP_STARTUP flags: IMMEDIATE flags change mid-run, so a startup copy would go stale. */
    @Throws(IllegalArgumentException::class)
    fun isEnabled(flag: FeatureFlag): Boolean {
        require(flag.activationMode == FeatureActivationMode.APP_STARTUP) { "${flag.key} is not APP_STARTUP" }
        return flag.key in keys
    }
}

/**
 * Platform storage for the startup flags, readable before SDK init without loading the host's main
 * persisted state. Implementations must not call back into [StartupFlagsLatch]: its lock is not reentrant.
 */
interface IStartupFlagsStore {
    /** Keys from the last [write], or null only when nothing was ever written. Throw when unreadable. */
    @Throws(Exception::class)
    fun read(): Set<String>?

    /** Must be durable when it returns, since the next process reads it at startup. Throw on failure. */
    @Throws(Exception::class)
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
 * Reads the startup flags once per process and mirrors remote flags back for the next one.
 * Keep one instance per process: the first [snapshot] wins, including an unreadable one.
 */
class StartupFlagsLatch {
    private val lock = PlatformLock()
    private var snapshot: StartupFlags? = null

    /** [store] runs only until a snapshot is taken; null means the host has no store yet. */
    fun snapshot(store: () -> IStartupFlagsStore?): StartupFlags =
        lock.withLock {
            snapshot ?: read(store()).also { snapshot = it }
        }

    /** Writes the APP_STARTUP subset of [remoteKeys] for the next process. Returns false when unchanged. */
    @Throws(Exception::class)
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
