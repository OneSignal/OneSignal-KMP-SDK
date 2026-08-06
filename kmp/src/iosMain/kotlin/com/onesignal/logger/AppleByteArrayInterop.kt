package com.onesignal.logger

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Copies logger payloads across the Kotlin/Native boundary without boxing each byte.
 */
@OptIn(ExperimentalForeignApi::class)
object AppleByteArrayInterop {
    fun toNSData(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) {
            return NSData.create(bytes = null, length = 0u)
        }
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
        }
    }

    fun toByteArray(data: NSData): ByteArray {
        if (data.length == 0uL) {
            return ByteArray(0)
        }
        return ByteArray(data.length.toInt()).apply {
            usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
    }
}
