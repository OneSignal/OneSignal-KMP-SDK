package com.onesignal.logger.internal

import com.onesignal.logger.ILogFileStore
import com.onesignal.logger.ILogTelemetryCrash
import com.onesignal.logger.LogRecord
import com.onesignal.logger.attributes.LogFieldsPerEvent
import com.onesignal.logger.attributes.LogFieldsTopLevel
import com.onesignal.logger.otlp.EncodableRecord
import com.onesignal.logger.otlp.OtlpLogEncoder

/**
 * Crash telemetry sink: encodes each record to OTLP/protobuf and writes it to the
 * injected [ILogFileStore] immediately. This is what makes crash reports survive a
 * process death — they are durably persisted before the process exits and shipped on
 * the next launch by the crash uploader.
 *
 * Records are stored fully self-contained (resource attributes baked in at capture
 * time) so the uploader can POST the stored bytes verbatim.
 *
 * [ILogFileStore.save] is intentionally blocking; this class stays `suspend` only
 * because resource attributes may need [ILoggerPlatformProvider.getInstallId].
 */
internal class LogTelemetryCrashImpl(
    private val fileStore: ILogFileStore,
    private val topLevelFields: LogFieldsTopLevel,
    private val perEventFields: LogFieldsPerEvent,
) : ILogTelemetryCrash {
    override suspend fun emit(record: LogRecord) {
        val resourceAttributes = topLevelFields.getAttributes()
        // Record attributes must win: a crash record's thread.name is the faulting thread,
        // which the per-event emitting thread would otherwise overwrite.
        val merged = perEventFields.getAttributes() + record.attributes
        val encodable =
            EncodableRecord(
                severity = record.severity,
                body = record.body,
                attributes = merged,
                timeUnixNanos = record.timestampNanos ?: epochNanosNow(),
                boolAttributes = record.boolAttributes,
            )
        // Bounded at capture time: the uploader posts these bytes verbatim, one record per
        // request, so there is no later point at which an over-cap record could be split.
        val bytes = OtlpLogEncoder.encodeBounded(resourceAttributes, encodable, OtlpLogEncoder.MAX_PAYLOAD_BYTES)
        if (!fileStore.save(bytes)) {
            throw IllegalStateException("ILogFileStore.save returned false")
        }
    }

    // Writes are synchronous in emit(); nothing is buffered, so flush is a no-op.
    override suspend fun forceFlush() = Unit

    override fun shutdown() = Unit
}
