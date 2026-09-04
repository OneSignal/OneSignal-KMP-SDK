package com.onesignal.logger

import com.onesignal.features.FeatureFlag
import com.onesignal.features.IFeatureFlagReader
import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.internal.LogTelemetryRemoteImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ObservabilityEventRecorderTest {
    private val event = ObservabilityEvent.DEVICE_GESTURE

    private fun recorder(
        scope: CoroutineScope,
        flags: IFeatureFlagReader = IFeatureFlagReader { true },
        logger: ILogger = RecordingLogger(),
        maxQueued: Int = ObservabilityEventRecorder.DEFAULT_MAX_QUEUED,
        processCap: Int = ObservabilityEventRecorder.DEFAULT_PROCESS_CAP,
    ) = ObservabilityEventRecorder(flags, logger, maxQueued, processCap, scope)

    private fun List<LogRecord>.sequence(): List<String?> = map { it.attributes["n"] }

    private fun RecordingLogger.warnings(): List<String> = messages.filter { it.startsWith("W:") }

    private fun RecordingLogger.errors(): List<String> = messages.filter { it.startsWith("E:") }

    // ===== The record shape =====

    @Test
    fun recordShipsAnInfoRecordNamedAfterTheEvent() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope)
        recorder.attach(telemetry)

        recorder.record(event, mapOf("gesture.result" to "copied"))
        runCurrent()

        val record = telemetry.emitted.single()
        assertEquals(LogSeverity.INFO, record.severity)
        assertEquals("sdk.device_gesture", record.body)
        assertEquals("sdk.device_gesture", record.attributes[ObservabilityEventRecorder.EVENT_NAME_ATTRIBUTE])
        assertEquals("copied", record.attributes["gesture.result"])
        assertNotNull(record.timestampNanos)
        assertTrue(record.boolAttributes.isEmpty())
    }

    @Test
    fun theSingleArgumentOverloadCarriesOnlyTheEventName() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope)
        recorder.attach(telemetry)

        recorder.record(event)
        runCurrent()

        assertEquals(mapOf("event.name" to "sdk.device_gesture"), telemetry.emitted.single().attributes)
    }

    @Test
    fun callerAttributesCannotShadowTheEventName() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope)
        recorder.attach(telemetry)

        recorder.record(event, mapOf("event.name" to "something.else"))
        runCurrent()

        assertEquals("sdk.device_gesture", telemetry.emitted.single().attributes["event.name"])
    }

    // ===== The flag gate =====

    @Test
    fun recordDropsWhenTheEventFlagIsOff() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, flags = IFeatureFlagReader { false })

        recorder.record(event)
        recorder.attach(telemetry)
        recorder.record(event)
        runCurrent()

        assertTrue(telemetry.emitted.isEmpty())
    }

    @Test
    fun theFlagIsReadOnEveryRecord() = runTest {
        // IMMEDIATE flags flip mid-session; the recorder must not latch the first answer.
        var enabled = false
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, flags = IFeatureFlagReader { enabled })
        recorder.attach(telemetry)

        recorder.record(event, mapOf("n" to "off"))
        enabled = true
        recorder.record(event, mapOf("n" to "on"))
        runCurrent()

        assertEquals(listOf("on"), telemetry.emitted.sequence())
    }

    @Test
    fun theHostIsAskedOnlyForTheEventsOwnFlag() = runTest {
        val asked = mutableListOf<FeatureFlag>()
        val recorder = recorder(backgroundScope, flags = IFeatureFlagReader { asked.add(it) })
        recorder.attach(RecordingTelemetry())

        recorder.record(event)

        assertEquals(listOf(FeatureFlag.SDK_EVENT_DEVICE_GESTURE), asked)
    }

    // ===== The pre-attach queue =====

    @Test
    fun recordsBeforeAttachQueueAndFlushInOrder() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope)

        recorder.record(event, mapOf("n" to "1"))
        recorder.record(event, mapOf("n" to "2"))
        runCurrent()
        assertTrue(telemetry.emitted.isEmpty())

        recorder.attach(telemetry)
        runCurrent()

        assertEquals(listOf("1", "2"), telemetry.emitted.sequence())
    }

    @Test
    fun aFlushedQueueIsNotFlushedAgainOnTheNextAttach() = runTest {
        val first = RecordingTelemetry()
        val second = RecordingTelemetry()
        val recorder = recorder(backgroundScope)
        recorder.record(event, mapOf("n" to "1"))

        recorder.attach(first)
        recorder.attach(second)
        runCurrent()

        assertEquals(listOf("1"), first.emitted.sequence())
        assertTrue(second.emitted.isEmpty())
    }

    @Test
    fun thePreAttachQueueIsBounded() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, maxQueued = 2)

        recorder.record(event, mapOf("n" to "1"))
        recorder.record(event, mapOf("n" to "2"))
        recorder.record(event, mapOf("n" to "3"))
        recorder.attach(telemetry)
        runCurrent()

        assertEquals(listOf("1", "2"), telemetry.emitted.sequence())
    }

    @Test
    fun aRecordDroppedByTheFullQueueDoesNotConsumeTheCap() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, maxQueued = 1, processCap = 2)

        recorder.record(event, mapOf("n" to "1"))
        recorder.record(event, mapOf("n" to "2"))
        recorder.attach(telemetry)
        recorder.record(event, mapOf("n" to "3"))
        runCurrent()

        assertEquals(listOf("1", "3"), telemetry.emitted.sequence())
    }

    // ===== The per-process cap =====

    @Test
    fun theCapStopsFurtherEvents() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, processCap = 2)
        recorder.attach(telemetry)

        repeat(5) { recorder.record(event, mapOf("n" to "$it")) }
        runCurrent()

        assertEquals(listOf("0", "1"), telemetry.emitted.sequence())
    }

    @Test
    fun theCapCountsQueuedEvents() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, processCap = 2)

        recorder.record(event, mapOf("n" to "1"))
        recorder.record(event, mapOf("n" to "2"))
        recorder.attach(telemetry)
        recorder.record(event, mapOf("n" to "3"))
        runCurrent()

        assertEquals(listOf("1", "2"), telemetry.emitted.sequence())
    }

    @Test
    fun flagOffEventsDoNotConsumeTheCap() = runTest {
        var enabled = false
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, flags = IFeatureFlagReader { enabled }, processCap = 1)
        recorder.attach(telemetry)

        recorder.record(event, mapOf("n" to "off"))
        enabled = true
        recorder.record(event, mapOf("n" to "on"))
        runCurrent()

        assertEquals(listOf("on"), telemetry.emitted.sequence())
    }

    @Test
    fun theCapHoldsUnderConcurrentRecords() = runTest {
        // Eight threads racing the admission lock must not let a single extra record through.
        val telemetry = CountingTelemetry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = recorder(scope, processCap = 20)
        recorder.attach(telemetry)

        withContext(Dispatchers.Default) {
            List(8) { launch { repeat(25) { recorder.record(event) } } }.joinAll()
        }
        scope.coroutineContext.job.children.toList().joinAll()
        scope.cancel()

        assertEquals(20, telemetry.count())
    }

    // ===== Detach, replace, reset =====

    @Test
    fun detachQueuesUntilTheNextAttach() = runTest {
        val first = RecordingTelemetry()
        val second = RecordingTelemetry()
        val recorder = recorder(backgroundScope)
        recorder.attach(first)
        recorder.detach(first)

        recorder.record(event, mapOf("n" to "1"))
        runCurrent()
        assertTrue(first.emitted.isEmpty())

        recorder.attach(second)
        runCurrent()

        assertTrue(first.emitted.isEmpty())
        assertEquals(listOf("1"), second.emitted.sequence())
    }

    @Test
    fun detachOfTelemetryThatIsNotAttachedIsIgnored() = runTest {
        // iOS shares one recorder across remote loggers, and a logger that lost the install race
        // is shut down after the winner started; its detach must not strand the winner.
        val winner = RecordingTelemetry()
        val loser = RecordingTelemetry()
        val recorder = recorder(backgroundScope)
        recorder.attach(winner)

        recorder.detach(loser)
        recorder.record(event, mapOf("n" to "1"))
        runCurrent()

        assertEquals(listOf("1"), winner.emitted.sequence())
        assertTrue(loser.emitted.isEmpty())
    }

    @Test
    fun attachReplacesTheTelemetry() = runTest {
        val first = RecordingTelemetry()
        val second = RecordingTelemetry()
        val recorder = recorder(backgroundScope)

        recorder.attach(first)
        recorder.record(event, mapOf("n" to "1"))
        recorder.attach(second)
        recorder.record(event, mapOf("n" to "2"))
        runCurrent()

        assertEquals(listOf("1"), first.emitted.sequence())
        assertEquals(listOf("2"), second.emitted.sequence())
    }

    @Test
    fun resetDropsTheQueueAndKeepsTheAttachedTelemetry() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope)
        recorder.record(event, mapOf("n" to "old app"))

        recorder.reset()
        recorder.attach(telemetry)
        runCurrent()
        assertTrue(telemetry.emitted.isEmpty())

        recorder.reset()
        recorder.record(event, mapOf("n" to "new app"))
        runCurrent()

        assertEquals(listOf("new app"), telemetry.emitted.sequence())
    }

    @Test
    fun resetDoesNotRefundTheCap() = runTest {
        // The cap guards the process, not the app id; a reset must not hand out a second budget.
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, processCap = 1)
        recorder.record(event, mapOf("n" to "1"))

        recorder.reset()
        recorder.attach(telemetry)
        recorder.record(event, mapOf("n" to "2"))
        runCurrent()

        assertTrue(telemetry.emitted.isEmpty())
    }

    // ===== Fail-open =====

    @Test
    fun recordSurvivesAThrowingFlagCheck() = runTest {
        val logger = RecordingLogger()
        val telemetry = RecordingTelemetry()
        val recorder =
            recorder(backgroundScope, flags = IFeatureFlagReader { throw IllegalStateException("flags boom") }, logger = logger)
        recorder.attach(telemetry)

        recorder.record(event)
        runCurrent()

        assertTrue(telemetry.emitted.isEmpty())
        assertTrue(logger.warnings().any { "flags boom" in it })
        assertTrue(logger.errors().isEmpty())
    }

    @Test
    fun recordSurvivesAThrowingTelemetry() = runTest {
        val logger = RecordingLogger()
        val telemetry = RecordingTelemetry().apply { emitException = IllegalStateException("telemetry boom") }
        val recorder = recorder(backgroundScope, logger = logger)
        recorder.attach(telemetry)

        recorder.record(event)
        runCurrent()

        assertTrue(logger.warnings().any { "telemetry boom" in it })
        assertTrue(logger.errors().isEmpty())
    }

    @Test
    fun expectedDropsLogAtDebugNotWarn() = runTest {
        val logger = RecordingLogger()
        val recorder = recorder(backgroundScope, flags = IFeatureFlagReader { false }, logger = logger)

        recorder.record(event)

        assertTrue(logger.warnings().isEmpty())
        assertTrue(logger.messages.any { it.startsWith("D:") && "is off" in it })
    }

    @Test
    fun aThrowingHostLoggerCannotEscapeRecordOrAttach() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, flags = IFeatureFlagReader { false }, logger = ThrowingLogger())

        recorder.record(event)
        recorder.attach(telemetry)
        recorder.detach(telemetry)
        recorder.reset()
        runCurrent()

        assertTrue(telemetry.emitted.isEmpty())
    }

    @Test
    fun aThrowingEmitDoesNotStopTheRestOfAFlush() = runTest {
        val emitted = mutableListOf<LogRecord>()
        val telemetry =
            object : ILogTelemetry {
                private var calls = 0

                override suspend fun emit(record: LogRecord) {
                    if (calls++ == 0) throw IllegalStateException("first emit boom")
                    emitted.add(record)
                }

                override suspend fun forceFlush() = Unit

                override fun shutdown() = Unit
            }
        val recorder = recorder(backgroundScope)
        recorder.record(event, mapOf("n" to "1"))
        recorder.record(event, mapOf("n" to "2"))

        recorder.attach(telemetry)
        runCurrent()

        assertEquals(listOf("2"), emitted.sequence())
    }

    // ===== On the wire =====

    @Test
    fun eventsShipThroughTheRemoteTelemetryWithPerEventFields() = runTest {
        val provider = FakePlatformProvider()
        val http = FakeHttpSender()
        val remote =
            LogTelemetryRemoteImpl(
                platformProvider = provider,
                httpSender = http,
                topLevelFields = LogFieldsTopLevel(provider),
                perEventFields = LogFieldsPerEvent(provider),
                scope = backgroundScope,
            )
        val recorder = recorder(backgroundScope)
        recorder.attach(remote)

        recorder.record(
            event,
            mapOf("gesture.result" to "copied", "gesture.id_kind" to "subscription_id", "gesture.id" to "push-789"),
        )
        runCurrent()
        remote.forceFlush()

        val record = parseProto(http.sentRequests.single().body).message(1).message(2).message(2)
        // severity_number (2) == INFO (9)
        assertEquals(9L, record.first(2).varint)
        assertEquals("sdk.device_gesture", record.message(5).string(1))
        val attributes =
            record.all(6).map { parseProto(it.bytes()) }.associate { it.string(1) to it.message(2).string(1) }
        assertEquals("sdk.device_gesture", attributes["event.name"])
        assertEquals("copied", attributes["gesture.result"])
        assertEquals("subscription_id", attributes["gesture.id_kind"])
        assertEquals("push-789", attributes["gesture.id"])
        // The per-event fields every record on this telemetry carries.
        assertEquals("app-123", attributes["ossdk.app_id"])
        assertEquals("foreground", attributes["app.state"])
        val resourceAttrKeys =
            parseProto(http.sentRequests.single().body).message(1).message(1).all(1).map { parseProto(it.bytes()).string(1) }
        assertTrue("ossdk.install_id" in resourceAttrKeys)
    }
}
