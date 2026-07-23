package com.onesignal.logger

import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.internal.LogTelemetryRemoteImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogCrashTest {
    private fun remote(
        scope: kotlinx.coroutines.CoroutineScope,
        provider: FakePlatformProvider,
        http: ILogHttpSender,
    ) = LogTelemetryRemoteImpl(
        platformProvider = provider,
        httpSender = http,
        topLevelFields = LogFieldsTopLevel(provider),
        perEventFields = LogFieldsPerEvent(provider),
        scope = scope,
    )

    @Test
    fun reporterSavesFatalRecordWithExceptionAttributes() = runTest {
        val provider = FakePlatformProvider()
        val store = FakeFileStore()
        val crashTelemetry = LoggerFactory.createCrashLocalTelemetry(provider, store)
        val reporter = LoggerFactory.createCrashReporter(crashTelemetry, RecordingLogger())

        reporter.saveCrash(
            CrashData(
                threadName = "main",
                exceptionType = "java.lang.NullPointerException",
                exceptionMessage = "npe message",
                stacktrace = "stack...",
            ),
        )

        assertEquals(1, store.entries.size)
        val record = parseProto(store.entries[0].bytes).message(1).message(2).message(2)
        assertEquals("npe message", record.message(5).string(1))
        val attrKeys = record.all(6).map { parseProto(it.bytes()).string(1) }
        assertTrue("exception.type" in attrKeys)
        assertTrue("exception.message" in attrKeys)
        assertTrue("ossdk.exception.thread.name" in attrKeys)
        val resourceAttrKeys =
            parseProto(store.entries[0].bytes).message(1).message(1).all(1).map { parseProto(it.bytes()).string(1) }
        assertTrue("service.name" in resourceAttrKeys)
    }

    @Test
    fun saveCrashEmitsFatalSeverityTaggedFatalTrue() = runTest {
        val provider = FakePlatformProvider()
        val store = FakeFileStore()
        val crashTelemetry = LoggerFactory.createCrashLocalTelemetry(provider, store)
        val reporter = LoggerFactory.createCrashReporter(crashTelemetry, RecordingLogger())

        reporter.saveCrash(
            CrashData(threadName = "main", exceptionType = "E", exceptionMessage = "m", stacktrace = "s"),
        )

        val record = parseProto(store.entries[0].bytes).message(1).message(2).message(2)
        // severity_number (2) == FATAL (21)
        assertEquals(21L, record.first(2).varint)
        assertEquals(1L, fatalFlag(record))
    }

    @Test
    fun saveNonFatalEmitsWarnSeverityTaggedFatalFalse() = runTest {
        val provider = FakePlatformProvider()
        val store = FakeFileStore()
        val crashTelemetry = LoggerFactory.createCrashLocalTelemetry(provider, store)
        val reporter = LoggerFactory.createCrashReporter(crashTelemetry, RecordingLogger())

        reporter.saveNonFatal(
            CrashData(threadName = "main", exceptionType = "E", exceptionMessage = "m", stacktrace = "s"),
        )

        val record = parseProto(store.entries[0].bytes).message(1).message(2).message(2)
        // severity_number (2) == WARN (13)
        assertEquals(13L, record.first(2).varint)
        assertEquals(0L, fatalFlag(record))
    }

    // Reads the ossdk.crash.fatal AnyValue.bool_value (0/1) from an encoded LogRecord message.
    private fun fatalFlag(record: ProtoMessage): Long =
        record.all(6)
            .map { parseProto(it.bytes()) }
            .single { it.string(1) == "ossdk.crash.fatal" }
            .message(2)
            .first(2)
            .varint

    @Test
    fun uploaderSendsReadableReportsAndDeletesOnSuccess() = runTest {
        val provider = FakePlatformProvider(minFileAgeForReadMillis = 0)
        val store = FakeFileStore()
        store.seed("f1", "payload1".encodeToByteArray(), ageMillis = Long.MAX_VALUE)
        store.seed("f2", "payload2".encodeToByteArray(), ageMillis = Long.MAX_VALUE)
        val http = FakeHttpSender()
        val uploader =
            LoggerFactory.createCrashUploader(provider, remote(backgroundScope, provider, http), store, RecordingLogger())

        uploader.start()

        assertEquals(2, http.sentRequests.size)
        assertTrue("f1" in store.deletedIds)
        assertTrue("f2" in store.deletedIds)
    }

    @Test
    fun uploaderStopsOnFailureAndKeepsReports() = runTest {
        val provider = FakePlatformProvider()
        val store = FakeFileStore()
        store.seed("f1", "p1".encodeToByteArray(), ageMillis = Long.MAX_VALUE)
        store.seed("f2", "p2".encodeToByteArray(), ageMillis = Long.MAX_VALUE)
        val http = FakeHttpSender(defaultResponse = LogHttpResponse(success = false, statusCode = 500))
        val uploader =
            LoggerFactory.createCrashUploader(provider, remote(backgroundScope, provider, http), store, RecordingLogger())

        uploader.start()

        // Two passes (start sends, then again after the read-age delay), each stops at f1.
        assertEquals(2, http.sentRequests.size)
        assertTrue(store.deletedIds.isEmpty())
    }

    @Test
    fun uploaderStopsWhenExportThrowsAndKeepsReports() = runTest {
        val provider = FakePlatformProvider(minFileAgeForReadMillis = 0)
        val store = FakeFileStore()
        store.seed("f1", "p1".encodeToByteArray(), ageMillis = Long.MAX_VALUE)
        store.seed("f2", "p2".encodeToByteArray(), ageMillis = Long.MAX_VALUE)
        val http =
            object : ILogHttpSender {
                override suspend fun send(request: LogHttpRequest): LogHttpResponse {
                    throw RuntimeException("network boom")
                }
            }
        val uploader =
            LoggerFactory.createCrashUploader(provider, remote(backgroundScope, provider, http), store, RecordingLogger())

        uploader.start()

        assertTrue(store.deletedIds.isEmpty())
    }

    @Test
    fun uploaderNoOpWhenRemoteLoggingDisabled() = runTest {
        val provider = FakePlatformProvider(remoteLogLevel = "NONE")
        val store = FakeFileStore()
        store.seed("f1", "p".encodeToByteArray(), ageMillis = Long.MAX_VALUE)
        val http = FakeHttpSender()
        val uploader =
            LoggerFactory.createCrashUploader(provider, remote(backgroundScope, provider, http), store, RecordingLogger())

        uploader.start()

        assertEquals(0, http.sentRequests.size)
    }
}
