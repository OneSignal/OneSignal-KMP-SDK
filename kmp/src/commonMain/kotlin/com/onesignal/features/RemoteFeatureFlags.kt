package com.onesignal.features

/**
 * Result of the Turbine SDK feature-flags endpoint.
 *
 * @param enabledKeys Feature keys that should be treated as enabled for this device/SDK.
 * @param metadataJson Optional per-flag payload JSON object (flag id → object), encoded as a
 * string so hosts do not need kotlinx.serialization types on the public boundary. Parsed from
 * sibling keys in the response JSON. Persist as-is; decode with [FeatureFlagMetadata.parse]
 * when nested fields are needed.
 */
data class RemoteFeatureFlagsResult(
    val enabledKeys: List<String>,
    val metadataJson: String?,
) {
    companion object {
        val EMPTY = RemoteFeatureFlagsResult(emptyList(), null)
    }
}

/**
 * Per-flag payloads decoded from a persisted [RemoteFeatureFlagsResult.metadataJson] string.
 *
 * Lookup is by flag id; values are JSON object text. No generic Map on the Obj-C surface.
 */
class FeatureFlagMetadata private constructor(
    private val jsonObjectById: Map<String, String>,
) {
    fun jsonObjectForId(id: String): String? = jsonObjectById[id]

    fun ids(): List<String> = jsonObjectById.keys.toList()

    companion object {
        /**
         * @return `null` when [raw] is null or blank. Invalid JSON yields an empty instance.
         */
        fun parse(raw: String?): FeatureFlagMetadata? {
            if (raw.isNullOrBlank()) {
                return null
            }
            return FeatureFlagMetadata(FeatureFlagsJsonParser.parseStoredMetadataMap(raw))
        }
    }
}

/** Why a feature-flags fetch did not produce a trustworthy [RemoteFeatureFlagsResult]. */
enum class RemoteFeatureFlagsUnavailableReason {
    INVALID_APP_ID,
    INVALID_SDK_VERSION,
    NON_SUCCESS_HTTP,
    EMPTY_BODY,
    INVALID_JSON,
}

/**
 * Outcome of [FeatureFlagsClient.fetchRemoteFeatureFlags].
 *
 * Flat (non-sealed) so the Obj-C / Swift boundary stays usable. [isSuccess] true means
 * [result] is set and safe to apply (including an empty `features` array). Otherwise keep
 * previously cached flags and inspect [reason] / [statusCode] / [bodySnippet] for logging.
 *
 * Construct only via [success] / [unavailable] so success and result cannot disagree.
 */
data class RemoteFeatureFlagsFetchOutcome private constructor(
    val isSuccess: Boolean,
    val result: RemoteFeatureFlagsResult?,
    val reason: RemoteFeatureFlagsUnavailableReason?,
    val statusCode: Int?,
    val bodySnippet: String?,
) {
    val isUnavailable: Boolean
        get() = !isSuccess

    /**
     * True when [statusCode] is HTTP 4xx. Hosts log these at WARN (misconfiguration)
     * and other failures at DEBUG (transient).
     */
    val isClientError: Boolean
        get() {
            val code = statusCode ?: return false
            return isHttpClientErrorStatus(code)
        }

    companion object {
        fun success(result: RemoteFeatureFlagsResult): RemoteFeatureFlagsFetchOutcome =
            RemoteFeatureFlagsFetchOutcome(
                isSuccess = true,
                result = result,
                reason = null,
                statusCode = null,
                bodySnippet = null,
            )

        fun unavailable(
            reason: RemoteFeatureFlagsUnavailableReason,
            statusCode: Int? = null,
            bodySnippet: String? = null,
        ): RemoteFeatureFlagsFetchOutcome =
            RemoteFeatureFlagsFetchOutcome(
                isSuccess = false,
                result = null,
                reason = reason,
                statusCode = statusCode,
                bodySnippet = bodySnippet,
            )
    }
}
