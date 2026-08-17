package com.onesignal.features

/**
 * Shared Turbine feature-flags fetch: validate SDK label → build path → GET → parse.
 *
 * Hosts inject [IFeatureFlagsHttp] (Android wraps `IHttpClient`; iOS can wrap URLSession later).
 * Lifecycle, caching, and console logging stay on the platform.
 */
class FeatureFlagsClient(
    private val http: IFeatureFlagsHttp,
) {
    /**
     * Fetches remote feature flags for [appId] on [platform] at [sdkVersion].
     *
     * @param platform Turbine `:platform` segment (e.g. `"android"`, `"ios"`).
     * @param sdkVersion Same label as the host's `SDK-Version` header segment (e.g. `050801`).
     */
    @Throws(Exception::class)
    suspend fun fetchRemoteFeatureFlags(
        appId: String,
        platform: String,
        sdkVersion: String,
    ): RemoteFeatureFlagsFetchOutcome {
        if (!TurbineSdkFeatureFlagsPath.isValidAppIdSegment(appId)) {
            return RemoteFeatureFlagsFetchOutcome.unavailable(
                reason = RemoteFeatureFlagsUnavailableReason.INVALID_APP_ID,
            )
        }
        if (!TurbineSdkFeatureFlagsPath.isValidFeaturesSdkVersionLabel(sdkVersion)) {
            return RemoteFeatureFlagsFetchOutcome.unavailable(
                reason = RemoteFeatureFlagsUnavailableReason.INVALID_SDK_VERSION,
            )
        }

        val path =
            TurbineSdkFeatureFlagsPath.buildGetPath(
                appId = appId,
                platform = platform,
                sdkVersion = sdkVersion,
            )

        val response = http.get(path)
        val body = response.body
        if (!response.isSuccess) {
            return RemoteFeatureFlagsFetchOutcome.unavailable(
                reason = RemoteFeatureFlagsUnavailableReason.NON_SUCCESS_HTTP,
                statusCode = response.statusCode,
                bodySnippet = bodySnippet(body),
            )
        }
        if (body.isNullOrBlank()) {
            return RemoteFeatureFlagsFetchOutcome.unavailable(
                reason = RemoteFeatureFlagsUnavailableReason.EMPTY_BODY,
                statusCode = response.statusCode,
                bodySnippet = bodySnippet(body),
            )
        }

        val parsed = FeatureFlagsJsonParser.parseSuccessful(body)
        return if (parsed != null) {
            RemoteFeatureFlagsFetchOutcome.success(parsed)
        } else {
            RemoteFeatureFlagsFetchOutcome.unavailable(
                reason = RemoteFeatureFlagsUnavailableReason.INVALID_JSON,
                statusCode = response.statusCode,
                bodySnippet = bodySnippet(body),
            )
        }
    }

    companion object {
        /**
         * Max chars of an HTTP response body included in diagnostic snippets. Turbine error bodies
         * are tiny; this bounds worst-case size if an unexpected payload is returned.
         */
        const val LOG_BODY_SNIPPET_MAX_CHARS = 200

        fun bodySnippet(body: String?): String {
            if (body.isNullOrEmpty()) return "<empty>"
            val flattened = body.replace('\n', ' ').replace('\r', ' ')
            return if (flattened.length <= LOG_BODY_SNIPPET_MAX_CHARS) {
                flattened
            } else {
                flattened.take(LOG_BODY_SNIPPET_MAX_CHARS) + "…"
            }
        }
    }
}
