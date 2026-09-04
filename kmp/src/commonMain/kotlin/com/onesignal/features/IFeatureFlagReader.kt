package com.onesignal.features

/**
 * A host's answer to "is this catalog flag on right now". An interface rather than a function type
 * so Swift implements it as a protocol returning a plain Bool.
 */
fun interface IFeatureFlagReader {
    fun isEnabled(flag: FeatureFlag): Boolean
}
