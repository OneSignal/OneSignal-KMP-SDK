package com.onesignal.logger

/**
 * A single, fully platform-agnostic log record.
 *
 * This replaces the OpenTelemetry `LogRecordBuilder` / `LogRecordData` types that
 * the old `otel` module leaked across its public surface. A record carries only
 * its own (record-specific) attributes; the telemetry implementation merges in
 * the per-event and top-level/resource attributes at emit/encode time.
 *
 * @property severity log severity
 * @property body human-readable message (becomes the OTLP log body)
 * @property attributes record-specific string attributes (e.g. exception.* fields)
 * @property boolAttributes record-specific boolean attributes. Kept separate from
 *   [attributes] so they encode as OTLP `bool_value` (not the string `"true"`/`"false"`),
 *   matching the typed attributes the OpenTelemetry SDK produced — e.g. the
 *   `ossdk.crash.fatal` crash/non-fatal indicator.
 * @property timestampNanos event time in nanoseconds since the Unix epoch; when
 *   `null` the telemetry stamps it at emit time.
 */
data class LogRecord(
    val severity: LogSeverity,
    val body: String,
    val attributes: Map<String, String> = emptyMap(),
    val boolAttributes: Map<String, Boolean> = emptyMap(),
    val timestampNanos: Long? = null,
)
