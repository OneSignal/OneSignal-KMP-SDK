package com.onesignal.features

/** Small critical-section lock for shared feature state (JVM monitor / NSLock). */
internal expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
