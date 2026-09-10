package com.onesignal.logger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Simple in-memory fakes used across the logger module's common tests.
 */

internal class FakePlatformProvider(
    override var appId: String? = "app-123",
    override var onesignalId: String? = "os-456",
    override var pushSubscriptionId: String? = "push-789",
    override var appState: String = "foreground",
    override var processUptime: Long = 1234L,
    override var currentThreadName: String = "test-thread",
    override var enabledFeatureFlags: List<String> = emptyList(),
    override var kotlinVersion: String? = null,
    override var additionalVersionAttributes: Map<String, String> = emptyMap(),
    override var crashStoragePath: String = "/tmp/onesignal/crashes",
    override var minFileAgeForReadMillis: Long = 5_000L,
    override var isRemoteLoggingEnabled: Boolean = true,
    override var remoteLogLevel: String? = "ERROR",
    override var isExporterLoggingEnabled: Boolean = false,
    override var apiBaseUrl: String = "https://api.onesignal.com/",
) : ILoggerPlatformProvider {
    var installId: String = "install-abc"

    override suspend fun getInstallId(): String = installId
    override val sdkBase: String = "android"
    override val sdkBaseVersion: String = "5.9.5"
    override val appPackageId: String = "com.example.app"
    override val appVersion: String = "1.0.0"
    override val deviceManufacturer: String = "TestCo"
    override val deviceModel: String = "Pixel-Test"
    override val osName: String = "Android"
    override val osVersion: String = "14"
    override val osBuildId: String = "BUILD123"
    override val sdkWrapper: String? = null
    override val sdkWrapperVersion: String? = null
    override val appIdForHeaders: String get() = appId ?: ""
}

internal class FakeHttpSender(
    var responses: ArrayDeque<LogHttpResponse> = ArrayDeque(),
    var defaultResponse: LogHttpResponse = LogHttpResponse(success = true, statusCode = 200),
    /** Drained before [responses]; each entry makes one send throw, as a transport failure would. */
    var exceptions: ArrayDeque<Exception> = ArrayDeque(),
) : ILogHttpSender {
    private val mutex = Mutex()
    val sentRequests = mutableListOf<LogHttpRequest>()

    override suspend fun send(request: LogHttpRequest): LogHttpResponse {
        // Both queues are drained under the same lock as the recording. The retry tests are
        // the first to drive this repeatedly, and a fake that advertises thread-safety it
        // only half-provides is worse than one that makes no claim.
        val queued =
            mutex.withLock {
                sentRequests.add(request)
                // A queued exception short-circuits without consuming a response, so the two
                // queues stay independent the way callers expect.
                if (exceptions.isNotEmpty()) {
                    exceptions.removeFirst() to null
                } else {
                    null to (if (responses.isNotEmpty()) responses.removeFirst() else defaultResponse)
                }
            }
        queued.first?.let { throw it }
        return queued.second!!
    }

    fun lastBodyAsString(): String = sentRequests.last().body.decodeToString()
}

internal class FakeFileStore : ILogFileStore {
    data class Entry(val id: String, val bytes: ByteArray, val ageMillis: Long)

    val entries = mutableListOf<Entry>()
    val deletedIds = mutableListOf<String>()

    data class UnrecognizedEntry(val id: String, val ageMillis: Long)

    /** Legacy/foreign entries that [deleteUnrecognizedEntries] may remove. */
    val unrecognizedEntries = mutableListOf<UnrecognizedEntry>()
    val purgedUnrecognizedIds = mutableListOf<String>()
    private var counter = 0

    /** Age assigned to records saved via [save]; tests can tweak per scenario. */
    var savedAgeMillis: Long = Long.MAX_VALUE

    /** When set, [listReadable] throws instead of returning entries. */
    var listReadableException: Exception? = null

    /** When set, [deleteUnrecognizedEntries] throws instead of purging. */
    var deleteUnrecognizedException: Exception? = null

    override fun save(bytes: ByteArray): Boolean {
        entries.add(Entry("file-${counter++}", bytes, savedAgeMillis))
        return true
    }

    fun seed(id: String, bytes: ByteArray, ageMillis: Long) {
        entries.add(Entry(id, bytes, ageMillis))
    }

    fun seedUnrecognized(id: String, ageMillis: Long = Long.MAX_VALUE) {
        unrecognizedEntries.add(UnrecognizedEntry(id, ageMillis))
    }

    override suspend fun listReadable(minAgeMillis: Long): List<StoredLogFile> {
        listReadableException?.let { throw it }
        return entries
            .filter { it.ageMillis >= minAgeMillis && it.id !in deletedIds }
            .map { StoredLogFile(it.id, it.bytes) }
    }

    override suspend fun delete(id: String) {
        deletedIds.add(id)
        entries.removeAll { it.id == id }
    }

    override suspend fun deleteUnrecognizedEntries(minAgeMillis: Long): Int {
        deleteUnrecognizedException?.let { throw it }
        val toPurge = unrecognizedEntries.filter { it.ageMillis >= minAgeMillis }
        purgedUnrecognizedIds.addAll(toPurge.map { it.id })
        unrecognizedEntries.removeAll(toPurge.toSet())
        return toPurge.size
    }
}

internal class RecordingTelemetry : ILogTelemetry {
    val emitted = mutableListOf<LogRecord>()

    /** When set, every [emit] throws it instead of recording. */
    var emitException: Exception? = null

    override suspend fun emit(record: LogRecord) {
        emitException?.let { throw it }
        emitted.add(record)
    }

    override suspend fun forceFlush() = Unit

    override fun shutdown() = Unit
}

/** Counts emits under a mutex, for tests that record from several threads at once. */
internal class CountingTelemetry : ILogTelemetry {
    private val mutex = Mutex()
    private var emitted = 0

    override suspend fun emit(record: LogRecord) {
        mutex.withLock { emitted++ }
    }

    suspend fun count(): Int = mutex.withLock { emitted }

    override suspend fun forceFlush() = Unit

    override fun shutdown() = Unit
}

/** Every call throws, to prove callers keep their own never-throws contracts. */
internal class ThrowingLogger : ILogger {
    override fun error(message: String) = throw IllegalStateException("logger boom")

    override fun warn(message: String) = throw IllegalStateException("logger boom")

    override fun info(message: String) = throw IllegalStateException("logger boom")

    override fun debug(message: String) = throw IllegalStateException("logger boom")
}

internal class RecordingLogger : ILogger {
    val messages = mutableListOf<String>()

    override fun error(message: String) { messages.add("E:$message") }
    override fun warn(message: String) { messages.add("W:$message") }
    override fun info(message: String) { messages.add("I:$message") }
    override fun debug(message: String) { messages.add("D:$message") }
}
