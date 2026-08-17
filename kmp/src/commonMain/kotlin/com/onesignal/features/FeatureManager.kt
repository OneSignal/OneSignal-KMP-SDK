package com.onesignal.features

/**
 * In-process latch for [FeatureFlag] state.
 *
 * Hosts pass the latest trusted remote keys (from cache at process start, then
 * from [FeatureFlagsClient] after a successful fetch). Persistence, HTTP, and
 * lifecycle stay on the platform.
 *
 * Call [refresh] with [applyAppStartupFlags] `true` once at process start, then
 * `false` for later updates so [FeatureActivationMode.APP_STARTUP] flags do not
 * flip mid-run.
 */
class FeatureManager {
    private var featureStates: Map<FeatureFlag, Boolean> = emptyMap()

    fun isEnabled(feature: FeatureFlag): Boolean = featureStates[feature] ?: false

    /**
     * Canonical keys currently enabled for this process, after latching.
     * Order follows [FeatureFlag] declaration order.
     */
    fun enabledFeatureKeys(): List<String> {
        val snapshot = featureStates
        return FeatureFlag.entries.mapNotNull { flag ->
            if (snapshot[flag] == true) flag.key else null
        }
    }

    fun refresh(
        remoteKeys: List<String>,
        applyAppStartupFlags: Boolean,
    ) {
        refresh(remoteKeys, applyAppStartupFlags, emptyList())
    }

    /**
     * @param remoteKeys Keys from the last trusted fetch or cache.
     * @param applyAppStartupFlags `true` on first load this process; `false` on later updates.
     * @param localOverrides Extra keys treated as enabled (test/debug only).
     */
    fun refresh(
        remoteKeys: List<String>,
        applyAppStartupFlags: Boolean,
        localOverrides: List<String>,
    ) {
        val enabledKeys =
            (remoteKeys.asSequence() + localOverrides.asSequence())
                .map { canonicalizeFeatureFlagId(it) }
                .toSet()

        val nextStates = featureStates.toMutableMap()
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
                    }
                }
            }
        }
        featureStates = nextStates
    }
}
