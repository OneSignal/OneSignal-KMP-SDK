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
 * In-process latch for [FeatureFlag] state.
 *
 * Hosts pass the latest trusted remote keys (from cache at process start, then
 * from [FeatureFlagsClient] after a successful fetch). Persistence, HTTP, and
 * lifecycle stay on the platform.
 *
 * Call [refresh] with [applyAppStartupFlags] `true` once at process start, then
 * `false` for later updates so [FeatureActivationMode.APP_STARTUP] flags do not
 * flip mid-run. [refresh] returns any APP_STARTUP changes that were held.
 *
 * State updates are guarded by an internal lock so [isEnabled] / [refresh] /
 * [enabledFeatureKeys] are safe across host threads.
 */
class FeatureManager {
    private val lock = PlatformLock()
    private var featureStates: Map<FeatureFlag, Boolean> = emptyMap()

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
     * @param applyAppStartupFlags `true` on first load this process; `false` on later updates.
     * @param localOverrides Extra keys treated as enabled (test/debug only).
     * @return APP_STARTUP flags whose remote desired value differed from the
     * latched run value and were therefore not applied.
     */
    fun refresh(
        remoteKeys: List<String>,
        applyAppStartupFlags: Boolean,
        localOverrides: List<String>,
    ): List<DeferredFeatureActivation> =
        lock.withLock {
            val enabledKeys =
                (remoteKeys.asSequence() + localOverrides.asSequence())
                    .map { canonicalizeFeatureFlagId(it) }
                    .toSet()

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
                        if (applyAppStartupFlags || !alreadyLatched) {
                            nextStates[feature] = desired
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
            deferred
        }
}
