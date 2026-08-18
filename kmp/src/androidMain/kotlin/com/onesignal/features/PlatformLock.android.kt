package com.onesignal.features

internal actual class PlatformLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}
