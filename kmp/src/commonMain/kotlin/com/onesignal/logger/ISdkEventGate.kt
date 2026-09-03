package com.onesignal.logger

/**
 * The host's feature-manager read for [SdkEvent.flag]. An interface rather than a function type so
 * Swift implements it as a protocol returning a plain Bool.
 */
fun interface ISdkEventGate {
    fun isEnabled(event: SdkEvent): Boolean
}
