package com.onesignal.logger

import kotlin.test.Test
import kotlin.test.assertContentEquals

class AppleByteArrayInteropTest {
    @Test
    fun roundTripsBytesThroughNSData() {
        val input = byteArrayOf(0, 1, 127, -1)

        val data = AppleByteArrayInterop.toNSData(input)
        val output = AppleByteArrayInterop.toByteArray(data)

        assertContentEquals(input, output)
    }

    @Test
    fun roundTripsEmptyBytesThroughNSData() {
        val input = byteArrayOf()

        val data = AppleByteArrayInterop.toNSData(input)
        val output = AppleByteArrayInterop.toByteArray(data)

        assertContentEquals(input, output)
    }
}
