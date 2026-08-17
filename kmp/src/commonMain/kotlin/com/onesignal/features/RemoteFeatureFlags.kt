package com.onesignal.features

import kotlinx.serialization.json.JsonObject

/**
 * Result of the Turbine SDK feature-flags endpoint.
 *
 * @param enabledKeys Feature keys that should be treated as enabled for this device/SDK.
 * @param metadata Optional per-flag payload (e.g. weights), keyed by flag id. Parsed from sibling
 * keys in the response JSON (see [FeatureFlagsJsonParser]).
 */
data class RemoteFeatureFlagsResult(
    val enabledKeys: List<String>,
    val metadata: JsonObject?,
) {
    companion object {
        val EMPTY = RemoteFeatureFlagsResult(emptyList(), null)
    }
}

/**
 * Outcome of [FeatureFlagsClient.fetchRemoteFeatureFlags].
 *
 * [Unavailable] means the client did not get a trustworthy response (HTTP error, invalid body,
 * etc.); callers should keep previously cached flags. [Success] includes a valid HTTP parse,
 * including an empty `features` array from the server.
 *
 * [reason] / [statusCode] / [bodySnippet] exist so the host can log at the right severity without
 * re-inspecting the HTTP layer.
 */
sealed class RemoteFeatureFlagsFetchOutcome {
    data class Success(val result: RemoteFeatureFlagsResult) : RemoteFeatureFlagsFetchOutcome()

    data class Unavailable(
        val reason: Reason,
        val statusCode: Int? = null,
        val bodySnippet: String? = null,
    ) : RemoteFeatureFlagsFetchOutcome() {
        /**
         * True when [statusCode] is HTTP 4xx. Hosts log these at WARN (misconfiguration)
         * and other failures at DEBUG (transient). iOS should use this instead of
         * re-deriving the 400–499 range.
         */
        val isClientError: Boolean
            get() {
                val code = statusCode ?: return false
                return isHttpClientErrorStatus(code)
            }

        enum class Reason {
            INVALID_SDK_VERSION,
            NON_SUCCESS_HTTP,
            EMPTY_BODY,
            INVALID_JSON,
        }
    }
}
