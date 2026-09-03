package com.onesignal.logger

import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.internal.LogTelemetryRemoteImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LogEventRecorderTest {
    private val event = SdkEvent.DEVICE_GESTURE

    private fun recorder(
        scope: CoroutineScope,
        isEnabled: (SdkEvent) -> Boolean = { true },
        logger: ILogger = RecordingLogger(),
        maxQueued: Int = LogEventRecorder.DEFAULT_MAX_QUEUED,
        sessionCap: Int = LogEventRecorder.DEFAULT_SESSION_CAP,
    ) = LogEventRecorder(scope, isEnabled, logger, maxQueued, sessionCap)

    private fun List<LogRecord>.sequence(): List<String?> = map { it.attributes["n"] }

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
        assertEquals("sdk.device_gesture", record.attributes[LogEventRecorder.EVENT_NAME_ATTRIBUTE])
        assertEquals("copied", record.attributes["gesture.result"])
        assertNotNull(record.timestampNanos)
        assertTrue(record.boolAttributes.isEmpty())
    }

    @Test
    fun recordWithoutAttributesCarriesOnlyTheEventName() = runTest {
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

    @Test
    fun recordDropsWhenTheEventFlagIsOff() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, isEnabled = { false })

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
        val recorder = recorder(backgroundScope, isEnabled = { enabled })
        recorder.attach(telemetry)

        recorder.record(event, mapOf("n" to "off"))
        enabled = true
        recorder.record(event, mapOf("n" to "on"))
        runCurrent()

        assertEquals(listOf("on"), telemetry.emitted.sequence())
    }

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
    fun aRecordDroppedByTheFullQueueDoesNotConsumeTheSessionCap() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, maxQueued = 1, sessionCap = 2)

        recorder.record(event, mapOf("n" to "1"))
        recorder.record(event, mapOf("n" to "2"))
        recorder.attach(telemetry)
        recorder.record(event, mapOf("n" to "3"))
        runCurrent()

        assertEquals(listOf("1", "3"), telemetry.emitted.sequence())
    }

    @Test
    fun theSessionCapStopsFurtherEvents() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, sessionCap = 2)
        recorder.attach(telemetry)

        repeat(5) { recorder.record(event, mapOf("n" to "$it")) }
        runCurrent()

        assertEquals(listOf("0", "1"), telemetry.emitted.sequence())
    }

    @Test
    fun theSessionCapCountsQueuedEvents() = runTest {
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, sessionCap = 2)

        recorder.record(event, mapOf("n" to "1"))
        recorder.record(event, mapOf("n" to "2"))
        recorder.attach(telemetry)
        recorder.record(event, mapOf("n" to "3"))
        runCurrent()

        assertEquals(listOf("1", "2"), telemetry.emitted.sequence())
    }

    @Test
    fun flagOffEventsDoNotConsumeTheSessionCap() = runTest {
        var enabled = false
        val telemetry = RecordingTelemetry()
        val recorder = recorder(backgroundScope, isEnabled = { enabled }, sessionCap = 1)
        recorder.attach(telemetry)

        recorder.record(event, mapOf("n" to "off"))
        enabled = true
        recorder.record(event, mapOf("n" to "on"))
        runCurrent()

        assertEquals(listOf("on"), telemetry.emitted.sequence())
    }

    @Test
    fun detachQueuesUntilTheNextAttach() = runTest {
        val first = RecordingTelemetry()
        val second = RecordingTelemetry()
        val recorder = recorder(backgroundScope)
        recorder.attach(first)
        recorder.detach()

        recorder.record(event, mapOf("n" to "1"))
        runCurrent()
        assertTrue(first.emitted.isEmpty())

        recorder.attach(second)
        runCurrent()

        assertTrue(first.emitted.isEmpty())
        assertEquals(listOf("1"), second.emitted.sequence())
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
    fun recordSurvivesAThrowingFlagCheck() = runTest {
        val logger = RecordingLogger()
        val telemetry = RecordingTelemetry()
        val recorder =
            recorder(backgroundScope, isEnabled = { throw IllegalStateException("flags boom") }, logger = logger)
        recorder.attach(telemetry)

        recorder.record(event)
        runCurrent()

        assertTrue(telemetry.emitted.isEmpty())
        assertTrue(logger.messages.any { it.startsWith("D:") && "flags boom" in it })
        assertTrue(logger.messages.none { it.startsWith("E:") || it.startsWith("W:") })
    }

    @Test
    fun recordSurvivesAThrowingTelemetry() = runTest {
        val logger = RecordingLogger()
        val telemetry = RecordingTelemetry().apply { emitException = IllegalStateException("telemetry boom") }
        val recorder = recorder(backgroundScope, logger = logger)
        recorder.attach(telemetry)

        recorder.record(event)
        runCurrent()

        assertTrue(logger.messages.any { it.startsWith("D:") && "telemetry boom" in it })
        assertTrue(logger.messages.none { it.startsWith("E:") || it.startsWith("W:") })
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
