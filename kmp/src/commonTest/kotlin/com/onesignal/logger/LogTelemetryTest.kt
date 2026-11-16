package com.onesignal.logger

import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.internal.LogTelemetryCrashImpl
import com.onesignal.logger.internal.LogTelemetryRemoteImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTelemetryTest {
    private fun remote(
        scope: kotlinx.coroutines.CoroutineScope,
        provider: FakePlatformProvider,
        http: FakeHttpSender,
    ) = LogTelemetryRemoteImpl(
        platformProvider = provider,
        httpSender = http,
        topLevelFields = LogFieldsTopLevel(provider),
        perEventFields = LogFieldsPerEvent(provider),
        scope = scope,
    )

    @Test
    fun emitThenForceFlushPostsOtlpProtobufToCorrectEndpoint() = runTest {
        val provider = FakePlatformProvider()
        val http = FakeHttpSender()
        val telemetry = remote(backgroundScope, provider, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "hello", mapOf("custom" to "v")))
        telemetry.forceFlush()

        assertEquals(1, http.sentRequests.size)
        val req = http.sentRequests[0]
        assertEquals("https://api.onesignal.com/sdk/log?app_id=app-123", req.url)
        assertEquals("application/x-protobuf", req.contentType)
        assertEquals("onesignal/android/5.9.5", req.headers["SDK-Version"])

        // Decode the OTLP/protobuf body and assert the record + resource attribute.
        val record = parseProto(req.body).message(1).message(2).message(2)
        assertEquals("hello", record.message(5).string(1))
        assertTrue(record.all(6).any { parseProto(it.bytes()).string(1) == "custom" })
        val resourceAttrKeys = parseProto(req.body).message(1).message(1).all(1).map { parseProto(it.bytes()).string(1) }
        assertTrue(resourceAttrKeys.contains("ossdk.install_id"))
    }

    @Test
    fun oversizedBatchIsSplitAcrossRequestsInsteadOfDropped() = runTest {
        val provider = FakePlatformProvider()
        val http = FakeHttpSender()
        val telemetry = remote(backgroundScope, provider, http)

        // Each record carries a ~32 KB stacktrace, so one batch far exceeds the payload cap.
        repeat(8) {
            telemetry.emit(LogRecord(LogSeverity.ERROR, "r$it", mapOf("exception.stacktrace" to "x".repeat(32_000))))
        }
        telemetry.forceFlush()

        assertTrue(http.sentRequests.size > 1, "expected several requests, got ${http.sentRequests.size}")
        http.sentRequests.forEach {
            assertTrue(it.body.size <= 65_536, "posted ${it.body.size} B, over the cap")
        }
        val bodies =
            http.sentRequests.flatMap { req ->
                parseProto(req.body).message(1).message(2).all(2).map {
                    parseProto(it.bytes()).message(5).string(1)
                }
            }
        assertEquals((0 until 8).map { "r$it" }, bodies)
    }

    @Test
    fun aChunkGivingUpWhileRetryableStopsTheRemainingChunks() = runTest {
        val provider = FakePlatformProvider()
        // Offline: every send is a transport failure, so no chunk can succeed.
        val http = FakeHttpSender(defaultResponse = LogHttpResponse(success = false, statusCode = -1))
        val telemetry = remote(backgroundScope, provider, http)

        repeat(8) {
            telemetry.emit(LogRecord(LogSeverity.ERROR, "r$it", mapOf("exception.stacktrace" to "x".repeat(32_000))))
        }
        val startMillis = testScheduler.currentTime
        telemetry.forceFlush()

        // One chunk's retry cycle, not four. The batch splits into 4 chunks, so without the
        // stop this is 20 posts and ~38s of backoff on a radio that is already failing.
        assertEquals(5, http.sentRequests.size)
        assertTrue(
            testScheduler.currentTime - startMillis < 15_000,
            "spent ${testScheduler.currentTime - startMillis} ms, more than a single retry cycle",
        )
    }

    @Test
    fun aPermanentlyRejectedChunkDoesNotStopItsSiblings() = runTest {
        val provider = FakePlatformProvider()
        val waf = WafBoundedSender()
        val telemetry =
            LogTelemetryRemoteImpl(
                platformProvider = provider,
                httpSender = waf,
                topLevelFields = LogFieldsTopLevel(provider),
                perEventFields = LogFieldsPerEvent(provider),
                scope = backgroundScope,
            )

        repeat(3) { telemetry.emit(LogRecord(LogSeverity.ERROR, "a$it", mapOf("k" to "y".repeat(20_000)))) }
        // Bodies are not truncated by the encoder, so this record is over the cap on its own
        // and is rejected 403 however it is split.
        telemetry.emit(LogRecord(LogSeverity.ERROR, "huge" + "B".repeat(200_000), emptyMap()))
        repeat(4) { telemetry.emit(LogRecord(LogSeverity.ERROR, "b$it", mapOf("k" to "y".repeat(20_000)))) }
        telemetry.forceFlush()

        val delivered =
            waf.sent.filter { it.body.size <= 65_536 }.flatMap { req ->
                parseProto(req.body).message(1).message(2).all(2).map {
                    parseProto(it.bytes()).message(5).string(1)
                }
            }
        // The rejected chunk sits in the middle, so stopping on it would silently drop b0..b3,
        // the same loss this fix removes.
        assertEquals((0 until 3).map { "a$it" } + (0 until 4).map { "b$it" }, delivered)
    }

    /** 403s anything over the WAF body cap, like the edge does, and accepts everything else. */
    private class WafBoundedSender : ILogHttpSender {
        val sent = mutableListOf<LogHttpRequest>()

        override suspend fun send(request: LogHttpRequest): LogHttpResponse {
            sent.add(request)
            val overCap = request.body.size > 65_536
            return LogHttpResponse(success = !overCap, statusCode = if (overCap) 403 else 200)
        }
    }

    @Test
    fun exportEncodedPostsRawBytes() = runTest {
        val provider = FakePlatformProvider()
        val http = FakeHttpSender()
        val telemetry = remote(backgroundScope, provider, http)

        val ok = telemetry.exportEncoded("raw-bytes".encodeToByteArray())

        assertTrue(ok)
        assertEquals("raw-bytes", http.lastBodyAsString())
    }

    @Test
    fun exportEncodedReturnsFalseOnHttpFailure() = runTest {
        val provider = FakePlatformProvider()
        val http = FakeHttpSender(defaultResponse = LogHttpResponse(success = false, statusCode = 500))
        val telemetry = remote(backgroundScope, provider, http)

        assertFalse(telemetry.exportEncoded("x".encodeToByteArray()))
    }

    @Test
    fun crashTelemetryWritesEncodedRecordToFileStore() = runTest {
        val provider = FakePlatformProvider()
        val store = FakeFileStore()
        val telemetry =
            LogTelemetryCrashImpl(store, LogFieldsTopLevel(provider), LogFieldsPerEvent(provider))

        telemetry.emit(LogRecord(LogSeverity.FATAL, "crash!", mapOf("exception.type" to "X")))

        assertEquals(1, store.entries.size)
        // Stored crash record is OTLP/protobuf; decode and assert its contents.
        val record = parseProto(store.entries[0].bytes).message(1).message(2).message(2)
        assertEquals("crash!", record.message(5).string(1))
        assertTrue(record.all(6).any { parseProto(it.bytes()).string(1) == "exception.type" })
        val resourceAttrKeys =
            parseProto(store.entries[0].bytes).message(1).message(1).all(1).map { parseProto(it.bytes()).string(1) }
        assertTrue(resourceAttrKeys.contains("ossdk.install_id"))
        assertTrue(resourceAttrKeys.contains("service.name"))
    }

    @Test
    fun shutdownFlushesPendingRemoteRecords() = runTest {
        val provider = FakePlatformProvider()
        val http = FakeHttpSender()
        // Dedicated scope: shutdown cancels it; don't cancel the test's backgroundScope.
        val scope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            )
        val telemetry = remote(scope, provider, http)

        telemetry.emit(LogRecord(LogSeverity.ERROR, "pending", emptyMap()))
        telemetry.shutdown()

        assertEquals(1, http.sentRequests.size)
    }
}
