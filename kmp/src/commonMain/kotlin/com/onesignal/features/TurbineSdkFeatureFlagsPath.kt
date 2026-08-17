package com.onesignal.features

/**
 * Builds the Turbine-relative path for SDK feature flags and validates path segments.
 *
 * Pure Kotlin only (no `java.*` / Android APIs).
 */
object TurbineSdkFeatureFlagsPath {
    private val FEATURES_SDK_VERSION_LABEL_REGEX = Regex("""^\d{6}(-[^/\s]+)?$""")

    /**
     * Labels expected in the `:sdk_version` path segment: six digits, optionally `-` and a non-empty
     * suffix (no slashes or whitespace).
     */
    fun isValidFeaturesSdkVersionLabel(label: String): Boolean = FEATURES_SDK_VERSION_LABEL_REGEX.matches(label)

    /**
     * App id path segment: non-blank after trim, and free of characters that would still be
     * ambiguous after percent-encoding (we reject `/`, `?`, `#` so hosts cannot accidentally
     * rewrite the path shape).
     */
    fun isValidAppIdSegment(appId: String): Boolean {
        val trimmed = appId.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        return trimmed.none { it == '/' || it == '?' || it == '#' }
    }

    /**
     * Path only (relative to API base), matching `apps/{app_id}/sdk/features/{platform}/{sdk_version}`.
     * [appId], [platform], and [sdkVersion] are UTF-8 percent-encoded per RFC 3986 (unreserved
     * bytes left as-is). Callers must validate [appId] / [sdkVersion] first via
     * [isValidAppIdSegment] / [isValidFeaturesSdkVersionLabel].
     */
    fun buildGetPath(
        appId: String,
        platform: String,
        sdkVersion: String,
    ): String {
        val a = percentEncodePathSegmentUtf8(appId.trim())
        val p = percentEncodePathSegmentUtf8(platform)
        val v = percentEncodePathSegmentUtf8(sdkVersion)
        return "apps/$a/sdk/features/$p/$v"
    }

    /**
     * Percent-encodes a UTF-8 string as a single path segment: unreserved bytes stay literal;
     * everything else becomes `%HH` (uppercase hex). Space becomes `%20` (not `+`).
     */
    fun percentEncodePathSegmentUtf8(segment: String): String {
        val bytes = segment.encodeToByteArray()
        return buildString(bytes.size * PCT_ENCODED_MAX_OUTPUT_CHARS_PER_INPUT_BYTE) {
            for (b in bytes) {
                val u = b.toInt() and BYTE_MASK
                if (isUnreservedByte(u)) {
                    append(u.toChar())
                } else {
                    append('%')
                    append(HEX_DIGITS[u shr HEX_NYBBLE_SHIFT])
                    append(HEX_DIGITS[u and HEX_NYBBLE_MASK])
                }
            }
        }
    }

    /** Per RFC 3986 section 2.3 unreserved (ALPHA / DIGIT / "-" / "." / "_" / "~"). */
    private fun isUnreservedByte(u: Int): Boolean =
        u in 'A'.code..'Z'.code ||
            u in 'a'.code..'z'.code ||
            u in '0'.code..'9'.code ||
            u == '-'.code ||
            u == '.'.code ||
            u == '_'.code ||
            u == '~'.code

    private const val BYTE_MASK: Int = 0xFF
    private const val PCT_ENCODED_MAX_OUTPUT_CHARS_PER_INPUT_BYTE: Int = 3
    private const val HEX_NYBBLE_MASK: Int = 0b1111
    private const val HEX_NYBBLE_SHIFT: Int = 4

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
