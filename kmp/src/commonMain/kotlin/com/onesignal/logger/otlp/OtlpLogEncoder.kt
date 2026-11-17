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

    // Reserved for the marker naming how much an elided value lost; the marker is always shorter.
    // Under the floor there is no room for a head, a marker and a tail, so the head alone is kept.
    private const val ELISION_MARKER_MAX_BYTES = 40
    private const val MIN_ELISION_BYTES = 128

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
    ): ByteArray = encodePayload(encodeResource(resourceAttributes), records.map { encodeLogRecord(it, UNBOUNDED) })

    /**
     * Encodes one record for the crash upload path, which posts a stored record on its own and so
     * can never rely on chunking. Exceeds [maxPayloadBytes] only when the resource attributes do.
     */
    fun encodeBounded(
        resourceAttributes: Map<String, String>,
        record: EncodableRecord,
        maxPayloadBytes: Int,
    ): ByteArray {
        val resource = encodeResource(resourceAttributes)
        val encoded = encodeLogRecord(record, UNBOUNDED)
        val payload = encodePayload(resource, listOf(encoded))
        if (payload.size <= maxPayloadBytes) return payload
        return trimToFit(resource, record, encoded, payload, maxPayloadBytes)
    }

    /** Splits into payloads of at most [maxPayloadBytes]. */
    fun encodeChunked(
        resourceAttributes: Map<String, String>,
        records: List<EncodableRecord>,
        maxPayloadBytes: Int,
    ): List<ByteArray> {
        if (records.isEmpty()) return emptyList()
        val resource = encodeResource(resourceAttributes)
        val encoded = records.map { encodeLogRecord(it, UNBOUNDED) }
        return buildList { appendChunks(resource, records, encoded, maxPayloadBytes) }
    }

    /**
     * Carries the encoded records down the bisection so each one is encoded once for the whole
     * split, rather than re-encoded at every level of the recursion.
     */
    private fun MutableList<ByteArray>.appendChunks(
        resource: ByteArray,
        records: List<EncodableRecord>,
        encoded: List<ByteArray>,
        maxPayloadBytes: Int,
    ) {
        val payload = encodePayload(resource, encoded)
        if (payload.size <= maxPayloadBytes) {
            add(payload)
            return
        }
        if (records.size == 1) {
            add(trimToFit(resource, records.single(), encoded.single(), payload, maxPayloadBytes))
            return
        }
        val mid = records.size / 2
        appendChunks(resource, records.subList(0, mid), encoded.subList(0, mid), maxPayloadBytes)
        appendChunks(resource, records.subList(mid, records.size), encoded.subList(mid, records.size), maxPayloadBytes)
    }

    /**
     * Budget from the measured overshoot rather than a constant, so this device's resource and
     * framing cost is already priced in. Takes [encoded] so the trim costs one more encode, not two.
     */
    private fun trimToFit(
        resource: ByteArray,
        record: EncodableRecord,
        encoded: ByteArray,
        payload: ByteArray,
        maxPayloadBytes: Int,
    ): ByteArray {
        val budget = encoded.size - (payload.size - maxPayloadBytes)
        // The resource attributes alone are over the cap; emptying the record would not save it.
        if (budget <= 0) return payload
        return encodePayload(resource, listOf(encodeLogRecord(record, budget)))
    }

    private fun encodePayload(resource: ByteArray, records: List<ByteArray>): ByteArray {
        val scopeLogs =
            ProtoWriter().apply {
                writeLengthDelimited(FIELD_SL_SCOPE, encodeScope())
                for (record in records) writeLengthDelimited(FIELD_SL_LOG_RECORDS, record)
            }.toByteArray()

        val resourceLogs =
            ProtoWriter().apply {
                writeLengthDelimited(FIELD_RL_RESOURCE, resource)
                writeLengthDelimited(FIELD_RL_SCOPE_LOGS, scopeLogs)
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

    private fun encodeScope(): ByteArray =
        ProtoWriter().apply { writeString(FIELD_SCOPE_NAME, SCOPE_NAME) }.toByteArray()

    private fun encodeLogRecord(record: EncodableRecord, maxBytes: Int): ByteArray {
        val writer = ProtoWriter()
        writer.writeFixed64(FIELD_LR_TIME_UNIX_NANO, record.timeUnixNanos)
        writer.writeVarintField(FIELD_LR_SEVERITY_NUMBER, record.severity.severityNumber.toLong())
        writer.writeString(FIELD_LR_SEVERITY_TEXT, record.severity.severityText)

        val pending = record.pendingValues()
        var dropped = record.boolAttributes.size + record.attributes.size - (pending.size - 1)
        fitWithinBudget(pending, maxBytes - RECORD_TRAILER_BYTES - writer.size).forEachIndexed { index, encoded ->
            if (encoded != null) writer.writeLengthDelimited(pending[index].field, encoded) else dropped++
        }

        writer.writeFixed64(FIELD_LR_OBSERVED_TIME_UNIX_NANO, record.timeUnixNanos)
        if (dropped > 0) writer.writeVarintField(FIELD_LR_DROPPED_ATTRIBUTES_COUNT, dropped.toLong())
        return writer.toByteArray()
    }

    /**
     * The body first, then bool attrs so reserved SDK flags (e.g. ossdk.crash.fatal) survive a
     * record near the count cap, then string attrs. Order within each group is by key.
     */
    private fun EncodableRecord.pendingValues(): List<PendingValue> =
        buildList {
            add(PendingValue(FIELD_LR_BODY, key = null, value = body, encoded = encodeBody(body)))
            var emitted = 0
            for ((key, value) in boolAttributes.entries.sortedBy { it.key }) {
                if (emitted++ >= MAX_ATTRIBUTE_COUNT) return@buildList
                add(PendingValue(FIELD_LR_ATTRIBUTES, key, value = null, encoded = encodeBoolKeyValue(key, value)))
            }
            for ((key, value) in attributes.sortedEntries()) {
                if (emitted++ >= MAX_ATTRIBUTE_COUNT) return@buildList
                // Holds the original, not the capped copy: a re-trim re-elides from the full value,
                // so a value cut twice still carries exactly one marker.
                add(PendingValue(FIELD_LR_ATTRIBUTES, key, value, encodeKeyValue(key, value.elideToUtf8Bytes(MAX_VALUE_BYTES))))
            }
        }

    /**
     * Equal shares, smallest first, so the slack small values leave passes to large ones. The body
     * competes too: a crash body is the exception message, and reserving it a third of the record
     * before anything else is weighed leaves the stacktrace a quarter.
     */
    private fun fitWithinBudget(pending: List<PendingValue>, budget: Int): List<ByteArray?> {
        if (pending.sumOf { fieldCost(it.encoded) } <= budget) return pending.map { it.encoded }
        val fitted = arrayOfNulls<ByteArray>(pending.size)
        // An attribute repeating the body verbatim (a crash record's exception.message) is
        // recoverable from it, so it drops instead of taking a share off everything else. Only
        // one big enough to claim a share: a message-less crash body is the exception type, and
        // dropping the exception.type that matches it would cost a filter and save nothing.
        val body = pending.first().value
        val share = budget / pending.size
        val contending =
            pending.indices.filter { it == 0 || pending[it].value != body || fieldCost(pending[it].encoded) <= share }
        var remaining = budget
        var unallocated = contending.size
        for (index in contending.sortedBy { fieldCost(pending[it].encoded) }) {
            val entry = pending[index]
            val cost = fieldCost(entry.encoded)
            val share = remaining / unallocated
            fitted[index] = if (cost <= share) entry.encoded else entry.truncatedTo(share)
            remaining -= fitted[index]?.let { fieldCost(it) } ?: 0
            unallocated--
        }
        return fitted.asList()
    }

    /** A bool attribute has no string value to shorten, so it is dropped rather than truncated. */
    private fun PendingValue.truncatedTo(budget: Int): ByteArray? {
        if (value == null) return null
        val overhead = if (key == null) 0 else fieldCost(encodeKeyValue(key, ""))
        val allowance = minOf(MAX_VALUE_BYTES, budget - overhead - VALUE_FRAMING_BYTES)
        // The body field is always written, even empty, so it is shortened rather than dropped.
        if (allowance <= 0) return if (key == null) encodeAnyValue("") else null
        val shortened = value.elideToUtf8Bytes(allowance)
        val truncated = if (key == null) encodeAnyValue(shortened) else encodeKeyValue(key, shortened)
        return if (fieldCost(truncated) <= budget) truncated else null
    }

    /** A body ([key] null) is a bare AnyValue; an attribute is a KeyValue. */
    private class PendingValue(val field: Int, val key: String?, val value: String?, val encoded: ByteArray)

    private fun encodeBody(body: String): ByteArray = encodeAnyValue(body.elideToUtf8Bytes(MAX_VALUE_BYTES))

    /** What one value costs on the record: its tag, its length prefix, and its own bytes. */
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
     * Keeps the head and the tail of an over-cap value. A stacktrace carries its frames at the
     * head and its `Caused by:` root cause at the tail, and a huge first-line message pushes the
     * frames out of the head, so cutting only the head can take the whole diagnosis with it.
     */
    private fun String.elideToUtf8Bytes(maxBytes: Int): String {
        val headEnd = prefixEndForUtf8Bytes(maxBytes)
        if (headEnd == length) return this
        if (maxBytes < MIN_ELISION_BYTES) return substring(0, headEnd)
        val content = maxBytes - ELISION_MARKER_MAX_BYTES
        val head = substring(0, prefixEndForUtf8Bytes(content * 2 / 3))
        val tail = substring(suffixStartForUtf8Bytes(content - head.utf8Size()))
        if (head.length + tail.length >= length) return substring(0, headEnd)
        // Own line, so a consumer reading the stacktrace line by line sees it as one bad line
        // rather than a corrupted frame.
        return "$head\n[... ${utf8Size() - head.utf8Size() - tail.utf8Size()} bytes elided ...]\n$tail"
    }

    /** Index just past the longest prefix within [maxBytes], never splitting a code point. */
    private fun String.prefixEndForUtf8Bytes(maxBytes: Int): Int {
        var bytes = 0
        var index = 0
        while (index < length) {
            val pair = isSurrogatePairAt(index)
            val charBytes = if (pair) 4 else utf8Length(this[index])
            if (bytes + charBytes > maxBytes) break
            bytes += charBytes
            index += if (pair) 2 else 1
        }
        return index
    }

    /** Index at which the longest suffix within [maxBytes] begins, never splitting a code point. */
    private fun String.suffixStartForUtf8Bytes(maxBytes: Int): Int {
        var bytes = 0
        var index = length
        while (index > 0) {
            val pair = index >= 2 && isSurrogatePairAt(index - 2)
            val charBytes = if (pair) 4 else utf8Length(this[index - 1])
            if (bytes + charBytes > maxBytes) break
            bytes += charBytes
            index -= if (pair) 2 else 1
        }
        return index
    }

    private fun String.utf8Size(): Int {
        var bytes = 0
        var index = 0
        while (index < length) {
            val pair = isSurrogatePairAt(index)
            bytes += if (pair) 4 else utf8Length(this[index])
            index += if (pair) 2 else 1
        }
        return bytes
    }

    private fun String.isSurrogatePairAt(index: Int): Boolean =
        this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()

    /** An unpaired surrogate encodes to at most 3 bytes, so the 3-byte case bounds it too. */
    private fun utf8Length(char: Char): Int =
        when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            else -> 3
        }
}
