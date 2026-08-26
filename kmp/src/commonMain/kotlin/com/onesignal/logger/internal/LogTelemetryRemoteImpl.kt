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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

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
    private val retrier: ExportRetrier = ExportRetrier(),
) : ILogTelemetryRemote {
    companion object {
        private const val MAX_QUEUE_SIZE = 100
        private const val MAX_BATCH_SIZE = 100
        private const val SCHEDULE_DELAY_MILLIS = 1_000L

        /**
         * Cap so a hung HTTP send cannot block app teardown indefinitely. It only has to
         * cover a single send, not a retry cycle: [shutdownSignal] stops the retrier first,
         * so nothing here can be waiting out a backoff. Deliberately shorter than either
         * platform sender's own 10s timeout — [shutdown] runs under `runBlocking`, and on
         * Android that is a lifecycle thread (a log-level change routes through it), so
         * dropping a hung batch beats blocking that thread for the sender's full timeout.
         */
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

    /** Completed by [shutdown] to collapse any in-flight retry cycle. */
    private val shutdownSignal = CompletableDeferred<Unit>()

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

    /**
     * Batched export retries transient failures in place. The batch being retried is the
     * only one held — records arriving meanwhile keep filling the processor's bounded
     * queue and are dropped past `maxQueueSize`, so memory stays capped at two batches.
     *
     * The retry runs under the processor's export mutex, which [forceFlush] and [shutdown]
     * both need, hence [shutdownSignal].
     */
    private suspend fun exportBatch(records: List<EncodableRecord>) {
        val payload = OtlpLogEncoder.encode(getResourceAttributes(), records)
        retrier.execute(abortSignal = shutdownSignal) { attemptPost(payload) }
    }

    override suspend fun exportEncoded(payload: ByteArray): Boolean = post(payload)

    private suspend fun attemptPost(payload: ByteArray): ExportAttempt =
        try {
            val response =
                httpSender.send(
                    LogHttpRequest(
                        url = endpoint,
                        headers = headers,
                        contentType = OtlpLogEncoder.CONTENT_TYPE,
                        body = payload,
                    ),
                )
            classifyStatus(response.success, response.statusCode)
        } catch (e: CancellationException) {
            // Not redundant with the catch below: CancellationException is an Exception in
            // Kotlin, so without this a cancelled scope is misread as a transient backend
            // failure and the retrier keeps going. On the paths that return without
            // suspending again — attempt cap reached, backoff budget spent, abort signalled —
            // nothing would rethrow it and the cancellation would be lost entirely.
            throw e
        } catch (_: Exception) {
            // A thrown sender is a transport failure, same as statusCode -1.
            ExportAttempt.RETRYABLE
        }

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
        // Stop the retrier before asking for the flush. Without this, teardown landing on a
        // backend blip queues behind a retry cycle whose backoffs alone outlast the flush
        // budget below: the batch is dropped anyway, only after blocking the caller for the
        // full timeout. The aborted batch is not requeued — it is mid-retry precisely because
        // the backend is rejecting it, so a final attempt would just cost another round trip.
        shutdownSignal.complete(Unit)

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
