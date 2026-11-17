package com.onesignal.logger.otlp

import com.onesignal.logger.LogSeverity

/**
 * A record ready to be encoded: severity/body/timestamp plus the FULLY-merged
 * attribute set (per-event + record-specific). Resource/top-level attributes are
 * supplied separately to the encoder.
 */
internal data class EncodableRecord(
    val severity: LogSeverity,
    val body: String,
    val attributes: Map<String, String>,
    val timeUnixNanos: Long,
    // Boolean-typed attributes, encoded as OTLP `bool_value` rather than a string. Kept
    // separate from [attributes] so a flag like `ossdk.crash.fatal` matches the typed
    // attribute the OpenTelemetry SDK previously produced.
    val boolAttributes: Map<String, Boolean> = emptyMap(),
)

/**
 * Encodes log records into **OTLP/protobuf** bytes (`Content-Type: application/x-protobuf`).
 *
 * This deliberately matches what the OpenTelemetry Java SDK's `OtlpHttpLogRecordExporter`
 * put on the wire (binary protobuf is its default; JSON is opt-in via `exportAsJson()`,
 * which the old `otel` module never called). Producing the same wire format here means
 * the OneSignal ingestion endpoint receives byte-for-byte the same kind of payload it
 * always has — the transport swapped from the OpenTelemetry SDK to this module, but the
 * bytes did not.
 *
 * Only the tiny subset of `opentelemetry-proto`'s `ExportLogsServiceRequest` needed for
 * string-valued log records is implemented, hand-rolled via [ProtoWriter] so it runs on
 * every Kotlin target with no dependency. Field numbers below come from
 * `opentelemetry/proto/logs/v1/logs.proto` and `.../common/v1/common.proto`.
 */
internal object OtlpLogEncoder {
    const val CONTENT_TYPE = "application/x-protobuf"

    /**
     * A Cloudflare WAF rule 403s POST /sdk/log bodies `gt 65536`, which the exporter reads as
     * permanent, so an over-cap payload is dropped with no retry. Largest body it admits.
     */
    const val MAX_PAYLOAD_BYTES = 65_536

    // Matches the old otel pipeline: ResourceConfig sets resource attribute
    // service.name=OneSignalDeviceSDK, and loggerBuilder("loggerBuilder") sets the
    // instrumentation scope name. Keeping these on the correct OTLP fields preserves
    // backend grouping/filters keyed on service.name.
    private const val SERVICE_NAME_KEY = "service.name"
    private const val SERVICE_NAME = "OneSignalDeviceSDK"
    private const val SCOPE_NAME = "loggerBuilder"

    // Mirrors the old otel module's LogLimits: 128 attributes per record, values truncated to
    // 32,000. Deliberately counted in UTF-8 bytes, where OTel counts characters and exempts the
    // body entirely: 32,000 CJK characters are 96,000 bytes, and the cap we have to fit under is
    // a byte cap. The body shares the value limit for the same reason.
    private const val MAX_ATTRIBUTE_COUNT = 128
    private const val MAX_VALUE_BYTES = 32_000

    /** Encode the record whole, leaving it to the caller to bound the payload. */
    private const val UNBOUNDED = Int.MAX_VALUE

    // Written after the attributes: observed_time_unix_nano (9 B) and, at most, a
    // dropped_attributes_count (6 B).
    private const val RECORD_TRAILER_BYTES = 16

    /** Upper bound on the tags and length prefixes wrapping one value, body or attribute. */
    private const val VALUE_FRAMING_BYTES = 16

    // ExportLogsServiceRequest
    private const val FIELD_RESOURCE_LOGS = 1

    // ResourceLogs
    private const val FIELD_RL_RESOURCE = 1
    private const val FIELD_RL_SCOPE_LOGS = 2

    // Resource
    private const val FIELD_RESOURCE_ATTRIBUTES = 1

    // ScopeLogs
    private const val FIELD_SL_SCOPE = 1
    private const val FIELD_SL_LOG_RECORDS = 2

    // InstrumentationScope
    private const val FIELD_SCOPE_NAME = 1

    // LogRecord
    private const val FIELD_LR_TIME_UNIX_NANO = 1
    private const val FIELD_LR_SEVERITY_NUMBER = 2
    private const val FIELD_LR_SEVERITY_TEXT = 3
    private const val FIELD_LR_BODY = 5
    private const val FIELD_LR_ATTRIBUTES = 6
    private const val FIELD_LR_DROPPED_ATTRIBUTES_COUNT = 7
    private const val FIELD_LR_OBSERVED_TIME_UNIX_NANO = 11

    // KeyValue
    private const val FIELD_KV_KEY = 1
    private const val FIELD_KV_VALUE = 2

    // AnyValue
    private const val FIELD_ANY_STRING_VALUE = 1
    private const val FIELD_ANY_BOOL_VALUE = 2

    fun encode(
        resourceAttributes: Map<String, String>,
        records: List<EncodableRecord>,
    ): ByteArray = encode(resourceAttributes, records, UNBOUNDED)

    /**
     * Encodes one record into a payload of at most [maxPayloadBytes], for the crash upload path,
     * which posts a stored record on its own and so can never rely on chunking to get under the cap.
     */
    fun encodeBounded(
        resourceAttributes: Map<String, String>,
        record: EncodableRecord,
        maxPayloadBytes: Int,
    ): ByteArray {
        val payload = encode(resourceAttributes, listOf(record))
        if (payload.size <= maxPayloadBytes) return payload
        return trimToFit(resourceAttributes, record, payload, maxPayloadBytes)
    }

    /** Splits into payloads of at most [maxPayloadBytes]. */
    fun encodeChunked(
        resourceAttributes: Map<String, String>,
        records: List<EncodableRecord>,
        maxPayloadBytes: Int,
    ): List<ByteArray> {
        if (records.isEmpty()) return emptyList()
        return buildList { appendChunks(resourceAttributes, records, maxPayloadBytes) }
    }

    private fun MutableList<ByteArray>.appendChunks(
        resourceAttributes: Map<String, String>,
        records: List<EncodableRecord>,
        maxPayloadBytes: Int,
    ) {
        val payload = encode(resourceAttributes, records)
        if (payload.size <= maxPayloadBytes) {
            add(payload)
            return
        }
        if (records.size == 1) {
            add(trimToFit(resourceAttributes, records.single(), payload, maxPayloadBytes))
            return
        }
        val mid = records.size / 2
        appendChunks(resourceAttributes, records.subList(0, mid), maxPayloadBytes)
        appendChunks(resourceAttributes, records.subList(mid, records.size), maxPayloadBytes)
    }

    /**
     * Re-encodes a record that cannot be split under a budget taken from the measured overshoot,
     * rather than a fixed constant, so whatever the resource attributes and framing cost on this
     * device is already accounted for.
     */
    private fun trimToFit(
        resourceAttributes: Map<String, String>,
        record: EncodableRecord,
        payload: ByteArray,
        maxPayloadBytes: Int,
    ): ByteArray {
        val budget = encodeLogRecord(record, UNBOUNDED).size - (payload.size - maxPayloadBytes)
        // The resource attributes alone are over the cap; emptying the record would not save it.
        if (budget <= 0) return payload
        return encode(resourceAttributes, listOf(record), budget)
    }

    private fun encode(
        resourceAttributes: Map<String, String>,
        records: List<EncodableRecord>,
        maxRecordBytes: Int,
    ): ByteArray {
        val resourceLogs =
            ProtoWriter().apply {
                writeLengthDelimited(FIELD_RL_RESOURCE, encodeResource(resourceAttributes))
                writeLengthDelimited(FIELD_RL_SCOPE_LOGS, encodeScopeLogs(records, maxRecordBytes))
            }.toByteArray()

        return ProtoWriter().apply {
            writeLengthDelimited(FIELD_RESOURCE_LOGS, resourceLogs)
        }.toByteArray()
    }

    private fun encodeResource(attributes: Map<String, String>): ByteArray {
        val merged =
            buildMap {
                putAll(attributes)
                // Authoritative — must win over any caller-supplied service.name.
                put(SERVICE_NAME_KEY, SERVICE_NAME)
            }
        val writer = ProtoWriter()
        for ((key, value) in merged.sortedEntries()) {
            writer.writeLengthDelimited(FIELD_RESOURCE_ATTRIBUTES, encodeKeyValue(key, value))
        }
        return writer.toByteArray()
    }

    private fun encodeScopeLogs(records: List<EncodableRecord>, maxRecordBytes: Int): ByteArray {
        val writer = ProtoWriter()
        writer.writeLengthDelimited(FIELD_SL_SCOPE, encodeScope())
        for (record in records) {
            writer.writeLengthDelimited(FIELD_SL_LOG_RECORDS, encodeLogRecord(record, maxRecordBytes))
        }
        return writer.toByteArray()
    }

    private fun encodeScope(): ByteArray =
        ProtoWriter().apply { writeString(FIELD_SCOPE_NAME, SCOPE_NAME) }.toByteArray()

    private fun encodeLogRecord(record: EncodableRecord, maxBytes: Int): ByteArray {
        val budget = maxBytes - RECORD_TRAILER_BYTES
        val writer = ProtoWriter()
        writer.writeFixed64(FIELD_LR_TIME_UNIX_NANO, record.timeUnixNanos)
        writer.writeVarintField(FIELD_LR_SEVERITY_NUMBER, record.severity.severityNumber.toLong())
        writer.writeString(FIELD_LR_SEVERITY_TEXT, record.severity.severityText)
        val bodyBytes = minOf(MAX_VALUE_BYTES, budget - writer.size - VALUE_FRAMING_BYTES)
        writer.writeLengthDelimited(FIELD_LR_BODY, encodeAnyValue(record.body.truncateToUtf8Bytes(bodyBytes)))
        val dropped = writer.writeAttributes(record, budget)
        writer.writeFixed64(FIELD_LR_OBSERVED_TIME_UNIX_NANO, record.timeUnixNanos)
        if (dropped > 0) writer.writeVarintField(FIELD_LR_DROPPED_ATTRIBUTES_COUNT, dropped.toLong())
        return writer.toByteArray()
    }

    /**
     * Writes the attributes that fit under the count cap and [budget], and returns how many
     * were left out, for OTLP's dropped_attributes_count. Attribute loss is otherwise silent.
     */
    private fun ProtoWriter.writeAttributes(record: EncodableRecord, budget: Int): Int {
        val pending = record.pendingAttributes()
        var dropped = record.boolAttributes.size + record.attributes.size - pending.size
        for (encoded in fitWithinBudget(pending, budget - size)) {
            if (encoded == null) dropped++ else writeLengthDelimited(FIELD_LR_ATTRIBUTES, encoded)
        }
        return dropped
    }

    /**
     * Bool attrs first so reserved SDK flags (e.g. ossdk.crash.fatal) are not dropped when a
     * record is near the count cap. Order within each type is by key.
     */
    private fun EncodableRecord.pendingAttributes(): List<PendingAttribute> =
        buildList {
            for ((key, value) in boolAttributes.entries.sortedBy { it.key }) {
                if (size >= MAX_ATTRIBUTE_COUNT) return@buildList
                add(PendingAttribute(key, value = null, encoded = encodeBoolKeyValue(key, value)))
            }
            for ((key, value) in attributes.sortedEntries()) {
                if (size >= MAX_ATTRIBUTE_COUNT) return@buildList
                val capped = value.truncateToUtf8Bytes(MAX_VALUE_BYTES)
                add(PendingAttribute(key, capped, encodeKeyValue(key, capped)))
            }
        }

    /**
     * Gives each attribute an equal share of the space left, smallest first, so the slack the
     * small ones leave passes to the large ones. A stacktrace can neither crowd out the identity
     * attributes a record needs to be groupable nor take the whole budget from another big value.
     */
    private fun fitWithinBudget(pending: List<PendingAttribute>, budget: Int): List<ByteArray?> {
        if (pending.sumOf { fieldCost(it.encoded) } <= budget) return pending.map { it.encoded }
        val fitted = arrayOfNulls<ByteArray>(pending.size)
        var remaining = budget
        var unallocated = pending.size
        for (index in pending.indices.sortedBy { fieldCost(pending[it].encoded) }) {
            val attribute = pending[index]
            val cost = fieldCost(attribute.encoded)
            val share = remaining / unallocated
            fitted[index] = if (cost <= share) attribute.encoded else attribute.truncatedTo(share)
            remaining -= fitted[index]?.let { fieldCost(it) } ?: 0
            unallocated--
        }
        return fitted.asList()
    }

    /** A bool attribute has no string value to shorten, so it is dropped rather than truncated. */
    private fun PendingAttribute.truncatedTo(budget: Int): ByteArray? {
        if (value == null) return null
        val allowance = budget - fieldCost(encodeKeyValue(key, "")) - VALUE_FRAMING_BYTES
        if (allowance <= 0) return null
        val truncated = encodeKeyValue(key, value.truncateToUtf8Bytes(allowance))
        return if (fieldCost(truncated) <= budget) truncated else null
    }

    private class PendingAttribute(val key: String, val value: String?, val encoded: ByteArray)

    /** What one attribute costs on the record: its tag, its length prefix, and its own bytes. */
    private fun fieldCost(encoded: ByteArray): Int = 1 + varintSize(encoded.size) + encoded.size

    private fun varintSize(value: Int): Int {
        var bytes = 1
        var remaining = value ushr 7
        while (remaining != 0) {
            bytes++
            remaining = remaining ushr 7
        }
        return bytes
    }

    private fun encodeKeyValue(key: String, value: String): ByteArray =
        ProtoWriter().apply {
            writeString(FIELD_KV_KEY, key)
            writeLengthDelimited(FIELD_KV_VALUE, encodeAnyValue(value))
        }.toByteArray()

    private fun encodeBoolKeyValue(key: String, value: Boolean): ByteArray =
        ProtoWriter().apply {
            writeString(FIELD_KV_KEY, key)
            writeLengthDelimited(FIELD_KV_VALUE, encodeBoolAnyValue(value))
        }.toByteArray()

    private fun encodeAnyValue(value: String): ByteArray =
        ProtoWriter().apply {
            // proto3 omits empty scalar fields; mirror that so an empty value decodes to
            // an unset oneof exactly as the OpenTelemetry marshaler produces.
            if (value.isNotEmpty()) writeString(FIELD_ANY_STRING_VALUE, value)
        }.toByteArray()

    private fun encodeBoolAnyValue(value: Boolean): ByteArray =
        ProtoWriter().apply {
            // Always write bool_value (even false): the flag is an explicitly-present oneof
            // member, so a `false` must decode to a definite false rather than an unset value.
            writeVarintField(FIELD_ANY_BOOL_VALUE, if (value) 1L else 0L)
        }.toByteArray()

    // Sort for deterministic output (stable payloads, easier testing/diffing).
    private fun Map<String, String>.sortedEntries(): List<Map.Entry<String, String>> = entries.sortedBy { it.key }

    /**
     * Cuts on a code point boundary, never mid-character and never between the halves of a
     * surrogate pair, so the truncated value is still valid UTF-8 on the wire.
     */
    private fun String.truncateToUtf8Bytes(maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        var bytes = 0
        var index = 0
        while (index < length) {
            val isPair = this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()
            // An unpaired surrogate encodes to at most 3 bytes, so the 3-byte case bounds it too.
            val charBytes = if (isPair) 4 else utf8Length(this[index])
            if (bytes + charBytes > maxBytes) return substring(0, index)
            bytes += charBytes
            index += if (isPair) 2 else 1
        }
        return this
    }

    private fun utf8Length(char: Char): Int =
        when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            else -> 3
        }
}
