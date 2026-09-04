package com.onesignal.logger

import com.onesignal.features.FeatureFlag
import com.onesignal.features.IFeatureFlagReader

/**
 * What an [ObservabilityEvent]'s catalog flag means for it. The evaluation lives here rather than
 * on the event so both polarities stay testable before any event uses them; enum entries cannot be
 * built in a test.
 */
internal enum class FlagPolarity {
    /** Off until the flag is present. The usual shape, a measurement switched on per app. */
    ENABLES,

    /** On until the flag is present. A kill switch, for a fact wanted from the first launch. */
    DISABLES,
    ;

    /** Whether the event may ship. A null [flag] means only the pipeline gate applies, like a crash. */
    fun allows(flag: FeatureFlag?, flags: IFeatureFlagReader): Boolean {
        if (flag == null) return true
        return flags.isEnabled(flag) == (this == ENABLES)
    }

    /** Why [allows] said no, for the debug line. */
    fun blockedBy(flag: FeatureFlag?): String =
        when {
            flag == null -> "nothing"
            this == ENABLES -> "${flag.key} is off"
            else -> "${flag.key} is on"
        }
}
