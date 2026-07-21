package com.onesignal.logger

import com.onesignal.logger.internal.LogBatchProcessor
import com.onesignal.logger.otlp.EncodableRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LogBatchProcessorTest {
    private fun rec(body: String) =
        EncodableRecord(LogSeverity.INFO, body, emptyMap(), 1L)

    @Test
    fun flushExportsBufferedRecordsAsSingleBatch() = runTest {
        val exported = mutableListOf<List<EncodableRecord>>()
        // Very large schedule delay so only the explicit flush triggers an export.
        val proc = LogBatchProcessor(backgroundScope, 100, 100, 100_000L) { exported.add(it) }

        proc.enqueue(rec("a"))
        proc.enqueue(rec("b"))
        proc.flush()

        assertEquals(1, exported.size)
        assertEquals(2, exported[0].size)
    }

    @Test
    fun dropsRecordsWhenQueueIsFull() = runTest {
        val exported = mutableListOf<List<EncodableRecord>>()
        val proc = LogBatchProcessor(backgroundScope, maxQueueSize = 2, maxBatchSize = 100, scheduleDelayMillis = 100_000L) {
            exported.add(it)
        }

        proc.enqueue(rec("a"))
        proc.enqueue(rec("b"))
        proc.enqueue(rec("c")) // dropped: queue already at capacity
        proc.flush()

        assertEquals(2, exported[0].size)
    }

    @Test
    fun sizeTriggerFlushesAutomatically() = runTest {
        val exported = mutableListOf<List<EncodableRecord>>()
        val proc =
            LogBatchProcessor(
                scope = backgroundScope,
                maxQueueSize = 100,
                maxBatchSize = 2,
                scheduleDelayMillis = 100_000L,
            ) { exported.add(it) }

        proc.enqueue(rec("a"))
        proc.enqueue(rec("b")) // hits maxBatchSize → flushSignal
        runCurrent()

        assertEquals(1, exported.size)
        assertEquals(2, exported[0].size)
    }

    @Test
    fun scheduleDelayTriggersFlush() = runTest {
        val exported = mutableListOf<List<EncodableRecord>>()
        val proc =
            LogBatchProcessor(
                scope = backgroundScope,
                maxQueueSize = 100,
                maxBatchSize = 100,
                scheduleDelayMillis = 1_000L,
            ) { exported.add(it) }

        proc.enqueue(rec("a"))
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(1, exported.size)
        assertEquals(listOf("a"), exported[0].map { it.body })
    }

    @Test
    fun exportExceptionDoesNotKillBatchLoop() = runTest {
        var calls = 0
        val proc =
            LogBatchProcessor<EncodableRecord>(
                scope = backgroundScope,
                maxQueueSize = 100,
                maxBatchSize = 1, // every enqueue auto-flushes via the background loop
                scheduleDelayMillis = 100_000L,
            ) {
                calls++
                if (calls == 1) throw RuntimeException("export failed")
            }

        proc.enqueue(rec("a"))
        runCurrent()
        assertEquals(1, calls)

        // Loop must still be alive after the first export threw.
        proc.enqueue(rec("b"))
        runCurrent()
        assertEquals(2, calls)
    }
}
