package com.onesignal.logger.internal

import com.onesignal.logger.ILogHttpSender
import com.onesignal.logger.ILogTelemetryRemote
import com.onesignal.logger.ILoggerPlatformProvider
import com.onesignal.logger.LogHttpRequest
import com.onesignal.logger.LogRecord
import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.otlp.EncodableRecord
import com.onesignal.logger.otlp.OtlpLogEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Remote telemetry sink: batches records and ships them as OTLP/protobuf over the
 * injected [ILogHttpSender]. Resource (top-level) attributes are computed once and
 * cached, mirroring the old SDK's resource caching.
 */
internal class LogTelemetryRemoteImpl(
    private val platformProvider: ILoggerPlatformProvider,
    private val httpSender: ILogHttpSender,
    private val topLevelFields: LogFieldsTopLevel,
    private val perEventFields: LogFieldsPerEvent,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ILogTelemetryRemote {
    companion object {
        private const val MAX_QUEUE_SIZE = 100
        private const val MAX_BATCH_SIZE = 100
        private const val SCHEDULE_DELAY_MILLIS = 1_000L
        /** Cap so a hung HTTP send cannot block app teardown indefinitely. */
        private const val SHUTDOWN_FLUSH_TIMEOUT_MILLIS = 5_000L
    }

    private val endpoint: String by lazy {
        LogEndpoint.build(platformProvider.apiBaseUrl, platformProvider.appIdForHeaders)
    }

    private val headers: Map<String, String> by lazy {
        mapOf(
            "SDK-Version" to "onesignal/${platformProvider.sdkBase}/${platformProvider.sdkBaseVersion}",
        )
    }

    private val resourceMutex = Mutex()
    private var cachedResourceAttributes: Map<String, String>? = null

    private val batchProcessor =
        LogBatchProcessor<EncodableRecord>(
            scope = scope,
            maxQueueSize = MAX_QUEUE_SIZE,
            maxBatchSize = MAX_BATCH_SIZE,
            scheduleDelayMillis = SCHEDULE_DELAY_MILLIS,
            onExport = ::exportBatch,
        )

    private suspend fun getResourceAttributes(): Map<String, String> =
        resourceMutex.withLock {
            cachedResourceAttributes ?: topLevelFields.getAttributes().also { cachedResourceAttributes = it }
        }

    override suspend fun emit(record: LogRecord) {
        val merged = perEventFields.getAttributes() + record.attributes
        batchProcessor.enqueue(
            EncodableRecord(
                severity = record.severity,
                body = record.body,
                attributes = merged,
                timeUnixNanos = record.timestampNanos ?: epochNanosNow(),
                boolAttributes = record.boolAttributes,
            ),
        )
    }

    private suspend fun exportBatch(records: List<EncodableRecord>) {
        val payload = OtlpLogEncoder.encode(getResourceAttributes(), records)
        post(payload)
    }

    override suspend fun exportEncoded(payload: ByteArray): Boolean = post(payload)

    private suspend fun post(payload: ByteArray): Boolean {
        val response =
            httpSender.send(
                LogHttpRequest(
                    url = endpoint,
                    headers = headers,
                    contentType = OtlpLogEncoder.CONTENT_TYPE,
                    body = payload,
                ),
            )
        return response.success
    }

    override suspend fun forceFlush() = batchProcessor.flush()

    override fun shutdown() {
        // Best-effort flush before teardown. Bounded so a hung sender cannot block
        // app disable/teardown; remaining buffered records are dropped on cancel.
        try {
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_FLUSH_TIMEOUT_MILLIS) { batchProcessor.flush() }
            }
        } catch (_: Exception) {
            // Still cancel so resources are released.
        }
        scope.cancel()
    }
}
