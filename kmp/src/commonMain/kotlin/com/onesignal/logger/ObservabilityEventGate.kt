package com.onesignal.logger

import com.onesignal.features.FeatureFlag
import com.onesignal.features.IFeatureFlagReader

/**
 * When an [ObservabilityEvent] may ship. The recorder evaluates it, so a host only answers flag
 * lookups. The pipeline gate, remote logging being on for the app, applies on top of every policy.
 */
internal sealed class ObservabilityEventGate {
    /** The catalog flag this gate reads, or null when it reads none. */
    abstract val flag: FeatureFlag?

    /** Off until [flag] is on. The default for a measurement switched on per app. */
    class RequiresFlag(override val flag: FeatureFlag) : ObservabilityEventGate()

    /** On until [flag] is on. For a fact wanted from the first launch, with a remote kill switch. */
    class UnlessFlag(override val flag: FeatureFlag) : ObservabilityEventGate()

    /** Always on; only the pipeline gate applies, like a crash record. */
    object Always : ObservabilityEventGate() {
        override val flag: FeatureFlag? = null
    }

    fun allows(flags: IFeatureFlagReader): Boolean =
        when (this) {
            is RequiresFlag -> flags.isEnabled(flag)
            is UnlessFlag -> !flags.isEnabled(flag)
            Always -> true
        }

    /** Why [allows] said no, for the debug line. */
    fun blockedBy(): String =
        when (this) {
            is RequiresFlag -> "${flag.key} is off"
            is UnlessFlag -> "${flag.key} is on"
            Always -> "nothing"
        }
}
