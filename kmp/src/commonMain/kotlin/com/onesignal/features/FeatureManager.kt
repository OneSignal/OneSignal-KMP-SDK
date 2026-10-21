package com.onesignal.features

/**
 * A latched [FeatureActivationMode.APP_STARTUP] flag whose remote value changed but was
 * not applied because it is already latched for this process.
 *
 * Hosts should log these at INFO so a mid-session Turbine flip is visible even
 * though [FeatureManager.isEnabled] keeps the latched value until the next run.
 */
data class DeferredFeatureActivation(
    val key: String,
    val desiredEnabled: Boolean,
    val latchedEnabled: Boolean,
)

/**
 * Thread-safe per-process latch for [FeatureFlag] state. Call [refreshAtStartup] once with the [StartupFlagsLatch] snapshot, then [refresh] with `false` after fetches.
 * `refresh(applyAppStartupFlags = true)` never reads the startup store, so APP_STARTUP flags would not match code that ran before init.
 */
class FeatureManager {
    private val lock = PlatformLock()
    private var featureStates: Map<FeatureFlag, Boolean> = emptyMap()
    private var hasCompletedFirstRefresh = false

    // Once set, APP_STARTUP flags latch from here and never from remote keys.
    private var startupKeys: Set<String>? = null

    fun isEnabled(feature: FeatureFlag): Boolean =
        lock.withLock {
            featureStates[feature] ?: false
        }

    /**
     * Canonical keys currently enabled for this process, after latching.
     * Order follows [FeatureFlag] declaration order.
     */
    fun enabledFeatureKeys(): List<String> =
        lock.withLock {
            val snapshot = featureStates
            FeatureFlag.entries.mapNotNull { flag ->
                if (snapshot[flag] == true) flag.key else null
            }
        }

    fun refresh(
        remoteKeys: List<String>,
        applyAppStartupFlags: Boolean,
    ): List<DeferredFeatureActivation> = refresh(remoteKeys, applyAppStartupFlags, emptyList())

    /**
     * @param remoteKeys Keys from the last trusted fetch or cache.
     * @param applyAppStartupFlags `true` on first load this process; `false` on later
     * updates. Honored only until the first [refresh] completes; later `true` values
     * cannot overwrite an already-latched APP_STARTUP flag.
     * @param localOverrides Extra keys treated as enabled (test/debug only).
     * @return APP_STARTUP flags whose remote desired value differed from the
     * latched run value and were therefore not applied.
     */
    fun refresh(
        remoteKeys: List<String>,
        applyAppStartupFlags: Boolean,
        localOverrides: List<String>,
    ): List<DeferredFeatureActivation> = lock.withLock { refreshLocked(remoteKeys, applyAppStartupFlags, localOverrides) }

    fun refreshAtStartup(
        remoteKeys: List<String>,
        startup: StartupFlags,
    ): List<DeferredFeatureActivation> = refreshAtStartup(remoteKeys, startup, emptyList())

    /**
     * APP_STARTUP flags latch from [startup] (even after an earlier [refresh]), IMMEDIATE flags from
     * [remoteKeys]. Startup flags whose remote value differs are returned as deferred. Only the first call latches.
     */
    fun refreshAtStartup(
        remoteKeys: List<String>,
        startup: StartupFlags,
        localOverrides: List<String>,
    ): List<DeferredFeatureActivation> =
        lock.withLock {
            val firstSnapshot = startupKeys == null
            if (firstSnapshot) startupKeys = startup.keys
            refreshLocked(remoteKeys, true, localOverrides, latchFromSnapshot = firstSnapshot)
        }

    // Callers hold [lock]; PlatformLock is not reentrant on iOS.
    private fun refreshLocked(
        remoteKeys: List<String>,
        applyAppStartupFlags: Boolean,
        localOverrides: List<String>,
        latchFromSnapshot: Boolean = false,
    ): List<DeferredFeatureActivation> {
        val overrideKeys = localOverrides.mapTo(HashSet()) { canonicalizeFeatureFlagId(it) }
        val enabledKeys = remoteKeys.mapTo(HashSet()) { canonicalizeFeatureFlagId(it) } + overrideKeys
        val startup = startupKeys

        val nextStates = featureStates.toMutableMap()
        val deferred = mutableListOf<DeferredFeatureActivation>()
        for (feature in FeatureFlag.entries) {
            val desired = feature.isEnabledIn(enabledKeys)
            when (feature.activationMode) {
                FeatureActivationMode.IMMEDIATE -> nextStates[feature] = desired
                FeatureActivationMode.APP_STARTUP -> {
                    // After the first refresh every catalog flag is present in
                    // [featureStates], including those that resolved to false.
                    val alreadyLatched = nextStates.containsKey(feature)
                    val applyStartup =
                        latchFromSnapshot || !alreadyLatched ||
                            (applyAppStartupFlags && !hasCompletedFirstRefresh)
                    if (applyStartup) {
                        val latched = if (startup != null) feature.key in startup || feature.key in overrideKeys else desired
                        nextStates[feature] = latched
                        if (latched != desired) {
                            deferred.add(DeferredFeatureActivation(feature.key, desiredEnabled = desired, latchedEnabled = latched))
                        }
                    } else {
                        val latched = nextStates[feature] ?: false
                        if (latched != desired) {
                            deferred.add(
                                DeferredFeatureActivation(
                                    key = feature.key,
                                    desiredEnabled = desired,
                                    latchedEnabled = latched,
                                ),
                            )
                        }
                    }
                }
            }
        }
        featureStates = nextStates
        hasCompletedFirstRefresh = true
        return deferred
    }
}
