package com.onesignal.logger

import com.onesignal.logger.otlp.EncodableRecord
import com.onesignal.logger.otlp.OtlpLogEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpLogEncoderTest {
    @Test
    fun encodesValidOtlpProtobufStructure() {
        val bytes =
            OtlpLogEncoder.encode(
                resourceAttributes = mapOf("ossdk.install_id" to "abc", "os.name" to "Android"),
                records =
                listOf(
                    EncodableRecord(
                        severity = LogSeverity.ERROR,
                        body = "boom",
                        attributes = mapOf("log.level" to "ERROR", "log.message" to "boom"),
                        timeUnixNanos = 1700000000000000000L,
                    ),
                ),
            )

        // ExportLogsServiceRequest.resource_logs (1) -> ResourceLogs
        val resourceLogs = parseProto(bytes).message(1)

        // ResourceLogs.resource (1) -> Resource, attributes (1) repeated KeyValue, sorted by key.
        val resourceAttrs = resourceLogs.message(1).all(1).map { parseProto(it.bytes()) }
        val resourceAttrMap =
            resourceAttrs.associate { it.string(1) to it.message(2).string(1) }
        // Wire-compat with old otel ResourceConfig: service.name on the resource.
        assertEquals("OneSignalDeviceSDK", resourceAttrMap["service.name"])
        assertEquals("Android", resourceAttrMap["os.name"])
        assertEquals("abc", resourceAttrMap["ossdk.install_id"])

        // ResourceLogs.scope_logs (2) -> ScopeLogs; scope name matches loggerBuilder(...).
        val scopeLogs = resourceLogs.message(2)
        assertEquals("loggerBuilder", scopeLogs.message(1).string(1))

        // ScopeLogs.log_records (2) -> LogRecord
        val record = scopeLogs.message(2)
        // time_unix_nano (1, fixed64) and observed_time_unix_nano (11, fixed64)
        assertEquals(1700000000000000000L, record.first(1).varint)
        assertEquals(1700000000000000000L, record.first(11).varint)
        // severity_number (2, varint) and severity_text (3, string)
        assertEquals(17L, record.first(2).varint)
        assertEquals("ERROR", record.string(3))
        // body (5) -> AnyValue.string_value (1)
        assertEquals("boom", record.message(5).string(1))
        // attributes (6) repeated, sorted: log.level then log.message
        val recordAttrs = record.all(6).map { parseProto(it.bytes()) }
        assertEquals("log.level", recordAttrs[0].string(1))
        assertEquals("log.message", recordAttrs[1].string(1))
        assertEquals("boom", recordAttrs[1].message(2).string(1))
    }

    @Test
    fun emptyStringValueOmittedToMatchProto3() {
        // An empty attribute value must produce an AnyValue with NO string_value field
        // set (matching proto3 / the OpenTelemetry marshaler), i.e. an empty message.
        val bytes =
            OtlpLogEncoder.encode(
                resourceAttributes = emptyMap(),
                records =
                listOf(
                    EncodableRecord(
                        severity = LogSeverity.INFO,
                        body = "b",
                        attributes = mapOf("empty" to ""),
                        timeUnixNanos = 1L,
                    ),
                ),
            )
        val record = parseProto(bytes).message(1).message(2).message(2)
        val attr = parseProto(record.all(6).single().bytes())
        assertEquals("empty", attr.string(1))
        // value (2) is present but its AnyValue message is empty (no field 1).
        assertTrue(attr.message(2).all(1).isEmpty())
    }

    @Test
    fun severityNumbersMatchOtelScheme() {
        assertEquals(1, LogSeverity.TRACE.severityNumber)
        assertEquals(5, LogSeverity.DEBUG.severityNumber)
        assertEquals(9, LogSeverity.INFO.severityNumber)
        assertEquals(13, LogSeverity.WARN.severityNumber)
        assertEquals(17, LogSeverity.ERROR.severityNumber)
        assertEquals(21, LogSeverity.FATAL.severityNumber)
    }

    @Test
    fun verboseMapsToTrace() {
        assertEquals(LogSeverity.TRACE, LogSeverity.fromLevelName("VERBOSE"))
        assertEquals(LogSeverity.INFO, LogSeverity.fromLevelName("nonsense"))
    }

    @Test
    fun contentTypeIsProtobuf() {
        assertEquals("application/x-protobuf", OtlpLogEncoder.CONTENT_TYPE)
    }

    @Test
    fun truncatesAttributeValuesToLogLimit() {
        // Matches the old otel LogLimits.maxAttributeValueLength (32,000). A long
        // exception.stacktrace value must be truncated on the wire.
        val longValue = "x".repeat(40_000)
        val bytes =
            OtlpLogEncoder.encode(
                resourceAttributes = emptyMap(),
                records =
                listOf(
                    EncodableRecord(
                        severity = LogSeverity.ERROR,
                        body = "boom",
                        attributes = mapOf("exception.stacktrace" to longValue),
                        timeUnixNanos = 1L,
                    ),
                ),
            )
        val record = parseProto(bytes).message(1).message(2).message(2)
        val attr = parseProto(record.all(6).single().bytes())
        assertEquals("exception.stacktrace", attr.string(1))
        assertTrue(attr.message(2).string(1).encodeToByteArray().size <= 32_000)
    }

    @Test
    fun encodesBoolAttributeAsTypedBoolValue() {
        // A boolean attribute (e.g. ossdk.crash.fatal) must encode as OTLP AnyValue.bool_value
        // (field 2 varint), NOT as the string "true" — matching the typed attribute the
        // OpenTelemetry SDK produced so the backend can segment on it.
        val bytes =
            OtlpLogEncoder.encode(
                resourceAttributes = emptyMap(),
                records =
                listOf(
                    EncodableRecord(
                        severity = LogSeverity.FATAL,
                        body = "boom",
                        attributes = mapOf("exception.type" to "E"),
                        timeUnixNanos = 1L,
                        boolAttributes = mapOf("ossdk.crash.fatal" to true),
                    ),
                ),
            )
        val record = parseProto(bytes).message(1).message(2).message(2)
        val attrs = record.all(6).map { parseProto(it.bytes()) }
        val fatal = attrs.single { it.string(1) == "ossdk.crash.fatal" }
        // AnyValue.bool_value (2) is a varint; true -> 1, and no string_value (1) is present.
        assertEquals(1L, fatal.message(2).first(2).varint)
        assertTrue(fatal.message(2).all(1).isEmpty())
    }

    @Test
    fun writesFalseBoolAttributeExplicitly() {
        // Unlike an empty string (which is omitted), a false bool is written explicitly so it
        // decodes to a definite false rather than an unset oneof.
        val bytes =
            OtlpLogEncoder.encode(
                resourceAttributes = emptyMap(),
                records =
                listOf(
                    EncodableRecord(
                        severity = LogSeverity.WARN,
                        body = "b",
                        attributes = emptyMap(),
                        timeUnixNanos = 1L,
                        boolAttributes = mapOf("ossdk.crash.fatal" to false),
                    ),
                ),
            )
        val record = parseProto(bytes).message(1).message(2).message(2)
        val fatal = parseProto(record.all(6).single().bytes())
        assertEquals("ossdk.crash.fatal", fatal.string(1))
        assertEquals(0L, fatal.message(2).first(2).varint)
    }

    @Test
    fun capsAttributeCountToLogLimit() {
        // Matches the old otel LogLimits.maxNumberOfAttributes (128).
        val manyAttrs = (0 until 200).associate { "k${it.toString().padStart(3, '0')}" to "v$it" }
        val bytes =
            OtlpLogEncoder.encode(
                resourceAttributes = emptyMap(),
                records =
                listOf(
                    EncodableRecord(
                        severity = LogSeverity.INFO,
                        body = "b",
                        attributes = manyAttrs,
                        timeUnixNanos = 1L,
                    ),
                ),
            )
        val record = parseProto(bytes).message(1).message(2).message(2)
        assertEquals(128, record.all(6).size)
    }

    @Test
    fun encodeChunkedReturnsNoPayloadsForEmptyBatch() {
        assertEquals(0, OtlpLogEncoder.encodeChunked(emptyMap(), emptyList(), 65_536).size)
    }

    @Test
    fun encodeChunkedLeavesUnderCapBatchAsSinglePayload() {
        val records = (0 until 5).map { bulkyRecord("r$it", valueLength = 10) }

        val payloads = OtlpLogEncoder.encodeChunked(RESOURCE_ATTRS, records, maxPayloadBytes = 65_536)

        assertEquals(1, payloads.size)
        // Identical to the unsplit encoding, so staying under the cap costs nothing on the wire.
        assertEquals(
            OtlpLogEncoder.encode(RESOURCE_ATTRS, records).toList(),
            payloads.single().toList(),
        )
    }

    @Test
    fun encodeChunkedSplitsOversizedBatchKeepingEveryRecord() {
        // 8 records x ~32 KB attribute is ~256 KB unsplit, four times the cap.
        val records = (0 until 8).map { bulkyRecord("r$it", valueLength = 32_000) }
        val cap = 65_536

        val payloads = OtlpLogEncoder.encodeChunked(RESOURCE_ATTRS, records, maxPayloadBytes = cap)

        assertTrue(payloads.size > 1, "expected the batch to be split, got ${payloads.size} payload(s)")
        payloads.forEach { assertTrue(it.size <= cap, "payload of ${it.size} B exceeds the $cap B cap") }
        // No record is dropped in the split, and none is duplicated across payloads.
        assertEquals(records.map { it.body }, payloads.flatMap { bodiesIn(it) })
    }

    @Test
    fun encodeChunkedTrimsSingleOversizedRecordUnderTheCap() {
        // A batch of one cannot be bisected, so the record itself is trimmed. Posting it whole
        // means a 403 at the WAF and silent loss of the record.
        val record = bulkyRecord("huge", valueLength = 32_000)

        val payloads = OtlpLogEncoder.encodeChunked(RESOURCE_ATTRS, listOf(record), maxPayloadBytes = 1_024)

        assertEquals(1, payloads.size)
        assertTrue(payloads.single().size <= 1_024, "payload of ${payloads.single().size} B exceeds the cap")
        assertEquals(listOf("huge"), bodiesIn(payloads.single()))
    }

    @Test
    fun truncatesAttributeValuesByBytesNotCharacters() {
        // The character-based limit let a 32,000-character CJK stacktrace reach 96,000 bytes,
        // over the whole-request cap on its own.
        val cjk = "字".repeat(32_000)

        val attr = soleAttributeOf(recordWith(attributes = mapOf("exception.stacktrace" to cjk)))

        val value = attr.message(2).string(1)
        assertTrue(value.encodeToByteArray().size <= 32_000, "value is ${value.encodeToByteArray().size} B")
        // Whole characters either side of the marker: a cut mid-character encodes to '?' or U+FFFD.
        assertTrue(value.startsWith("字") && value.endsWith("字"), "lost a boundary character")
        assertTrue('?' !in value && '\uFFFD' !in value, "cut mid-character")
    }

    @Test
    fun keepsTheTailSoACausedByChainSurvives() {
        // stackTraceToString puts the frames at the head and the root cause at the very tail, so
        // a head-only cut delivers a stacktrace with the one line anyone wanted missing.
        val stacktrace =
            "java.lang.IllegalStateException: boom\n" +
                "\tat com.onesignal.Top.frame(Top.kt:1)\n".repeat(2_000) +
                "Caused by: java.io.IOException: the actual root cause\n" +
                "\tat com.onesignal.Root.cause(Root.kt:9)\n"

        val attr = soleAttributeOf(recordWith(attributes = mapOf("exception.stacktrace" to stacktrace)))

        val value = attr.message(2).string(1)
        assertTrue(value.startsWith("java.lang.IllegalStateException: boom"), "lost the head")
        assertTrue(value.endsWith("\tat com.onesignal.Root.cause(Root.kt:9)\n"), "lost the tail")
        assertTrue("Caused by: java.io.IOException: the actual root cause" in value, "lost the root cause")
        assertTrue("bytes elided" in value, "elision is unmarked")
    }

    @Test
    fun neverSplitsASurrogatePair() {
        // Half a pair on the wire is invalid UTF-8, and both cut points can land inside one.
        val emoji = "\uD83D\uDCA5"

        val attr = soleAttributeOf(recordWith(attributes = mapOf("exception.message" to "a" + emoji.repeat(10_000))))

        val value = attr.message(2).string(1)
        assertTrue(value.encodeToByteArray().size <= 32_000, "value is ${value.encodeToByteArray().size} B")
        value.forEachIndexed { index, char ->
            if (char.isHighSurrogate()) {
                assertTrue(index + 1 < value.length && value[index + 1].isLowSurrogate(), "unpaired high at $index")
            }
            if (char.isLowSurrogate()) {
                assertTrue(index > 0 && value[index - 1].isHighSurrogate(), "unpaired low at $index")
            }
        }
    }

    @Test
    fun elidesAnOversizedBody() {
        // The body was never truncated; a 200,000-character one is three times the request cap.
        val body = ("head" + "x".repeat(200_000) + "tail")

        val value = recordWith(body = body, attributes = emptyMap()).message(5).string(1)

        assertTrue(value.encodeToByteArray().size <= 32_000, "body is ${value.encodeToByteArray().size} B")
        assertTrue(value.startsWith("head"))
        assertTrue(value.endsWith("tail"))
    }

    @Test
    fun keepsSmallAttributesWhenTrimmingALoneOversizedRecord() {
        val payload = OtlpLogEncoder.encodeBounded(RESOURCE_ATTRS, cjkCrashRecord(), OtlpLogEncoder.MAX_PAYLOAD_BYTES)

        assertTrue(payload.size <= OtlpLogEncoder.MAX_PAYLOAD_BYTES, "payload is ${payload.size} B")
        val logRecord = parseProto(payload).message(1).message(2).message(2)
        val attributes = logRecord.all(6).map { parseProto(it.bytes()) }.associate { it.string(1) to it.message(2) }
        assertTrue(
            setOf("exception.type", "log.record.uid", "ossdk.app_id", "ossdk.crash.fatal", "thread.name")
                .all { it in attributes.keys },
            "an identity attribute was crowded out: ${attributes.keys}",
        )
        assertEquals("main", attributes.getValue("thread.name").string(1))
        assertEquals(1L, attributes.getValue("ossdk.crash.fatal").first(2).varint)
    }

    @Test
    fun theBodyCompetesForBudgetRatherThanReservingItsOwn() {
        // Three distinct large values and no duplicate, so only the body's own allocation is in
        // play. Reserving it 32,000 bytes before the rest were weighed left them 16,000 each.
        val record =
            EncodableRecord(
                severity = LogSeverity.ERROR,
                body = "b".repeat(50_000),
                attributes = mapOf("first" to "f".repeat(50_000), "second" to "s".repeat(50_000)),
                timeUnixNanos = 1L,
            )

        val payload = OtlpLogEncoder.encodeBounded(RESOURCE_ATTRS, record, OtlpLogEncoder.MAX_PAYLOAD_BYTES)

        val logRecord = parseProto(payload).message(1).message(2).message(2)
        val sizes =
            (
                listOf(logRecord.message(5).string(1)) +
                    logRecord.all(6).map { parseProto(it.bytes()).message(2).string(1) }
                ).map { it.encodeToByteArray().size }
        // An even three-way split is about 21,500 each; the body hogging its reserve shows as 32,000.
        assertTrue(sizes.min() > 19_000, "shares are lopsided: $sizes")
        assertTrue(sizes.max() < 24_000, "a value took ${sizes.max()} B of the record")
    }

    @Test
    fun dropsAnAttributeThatRepeatsTheBody() {
        // exception.message is the body verbatim on every crash record, so when the record is over
        // budget the copy is recoverable from the body and shrinking both halves the stacktrace.
        val payload = OtlpLogEncoder.encodeBounded(RESOURCE_ATTRS, cjkCrashRecord(), OtlpLogEncoder.MAX_PAYLOAD_BYTES)

        val logRecord = parseProto(payload).message(1).message(2).message(2)
        val keys = logRecord.all(6).map { parseProto(it.bytes()).string(1) }
        assertTrue("exception.message" !in keys, "the duplicate survived: $keys")
        // Dropping it is only honest if the record says so.
        assertEquals(1L, logRecord.first(7).varint)
    }

    @Test
    fun keepsAnAttributeThatRepeatsTheBodyWhenTheRecordFits() {
        val record =
            EncodableRecord(
                severity = LogSeverity.ERROR,
                body = "boom",
                attributes = mapOf("exception.message" to "boom"),
                timeUnixNanos = 1L,
            )

        val payload = OtlpLogEncoder.encodeBounded(RESOURCE_ATTRS, record, OtlpLogEncoder.MAX_PAYLOAD_BYTES)

        val logRecord = parseProto(payload).message(1).message(2).message(2)
        assertEquals(listOf("exception.message"), logRecord.all(6).map { parseProto(it.bytes()).string(1) })
        assertTrue(logRecord.all(7).isEmpty())
    }

    /** Shaped like LogCrashReporter builds one: the body is the message, and the attribute repeats it. */
    private fun cjkCrashRecord(): EncodableRecord {
        val message = "字".repeat(20_000)
        return EncodableRecord(
            severity = LogSeverity.FATAL,
            body = message,
            attributes =
            mapOf(
                "exception.message" to message,
                "exception.stacktrace" to "java.lang.IllegalStateException: $message\n" + "\tat com.onesignal.A.b(A.kt:1)\n".repeat(500),
                "exception.type" to "java.lang.IllegalStateException",
                "log.record.uid" to "a-b-c",
                "ossdk.app_id" to "app",
                "thread.name" to "main",
            ),
            timeUnixNanos = 1L,
            boolAttributes = mapOf("ossdk.crash.fatal" to true),
        )
    }

    @Test
    fun reportsDroppedAttributeCount() {
        // Attribute loss was silent; OTLP has a field for it.
        val manyAttrs = (0 until 200).associate { "k${it.toString().padStart(3, '0')}" to "v$it" }

        val record = recordWith(attributes = manyAttrs)

        assertEquals(128, record.all(6).size)
        assertEquals(72L, record.first(7).varint)
    }

    @Test
    fun omitsDroppedAttributeCountWhenNothingIsDropped() {
        val record = recordWith(attributes = mapOf("exception.type" to "E"))

        assertTrue(record.all(7).isEmpty())
    }

    private fun recordWith(
        body: String = "boom",
        attributes: Map<String, String>,
    ): ProtoMessage {
        val bytes =
            OtlpLogEncoder.encode(
                resourceAttributes = emptyMap(),
                records =
                listOf(
                    EncodableRecord(
                        severity = LogSeverity.ERROR,
                        body = body,
                        attributes = attributes,
                        timeUnixNanos = 1L,
                    ),
                ),
            )
        return parseProto(bytes).message(1).message(2).message(2)
    }

    private fun soleAttributeOf(record: ProtoMessage): ProtoMessage = parseProto(record.all(6).single().bytes())

    private fun bulkyRecord(body: String, valueLength: Int) =
        EncodableRecord(
            severity = LogSeverity.ERROR,
            body = body,
            attributes = mapOf("exception.stacktrace" to "x".repeat(valueLength)),
            timeUnixNanos = 1L,
        )

    /** Record bodies in a payload, in wire order. */
    private fun bodiesIn(payload: ByteArray): List<String> =
        parseProto(payload).message(1).message(2).all(2).map {
            parseProto(it.bytes()).message(5).string(1)
        }

    private companion object {
        val RESOURCE_ATTRS = mapOf("ossdk.install_id" to "abc", "os.name" to "Android")
    }
}
