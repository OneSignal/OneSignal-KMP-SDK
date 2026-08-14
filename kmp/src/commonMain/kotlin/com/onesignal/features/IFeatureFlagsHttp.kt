package com.onesignal.features

/**
 * Result of a feature-flags GET. Status semantics mirror typical HTTP clients:
 * success is 2xx-ish handled by the host; [isClientError] is 4xx.
 */
data class FeatureFlagsHttpResponse(
    val statusCode: Int,
    val body: String?,
) {
    val isSuccess: Boolean
        get() = statusCode == 200 || statusCode == 201 || statusCode == 202 || statusCode == 304

    val isClientError: Boolean
        get() = statusCode in 400 until 500
}

/**
 * Platform-injected HTTP transport for the Turbine feature-flags GET.
 *
 * [relativePath] is the path returned by [TurbineSdkFeatureFlagsPath.buildGetPath] (no leading
 * slash); the host resolves it against its API base URL and attaches SDK auth headers.
 */
interface IFeatureFlagsHttp {
    @Throws(Exception::class)
    suspend fun get(relativePath: String): FeatureFlagsHttpResponse
}
