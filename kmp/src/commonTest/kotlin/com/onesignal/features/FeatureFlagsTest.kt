package com.onesignal.features

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurbineSdkFeatureFlagsPathTest {
    @Test
    fun percentEncodeLeavesUnreservedUnchanged() {
        assertEquals("android", TurbineSdkFeatureFlagsPath.percentEncodePathSegmentUtf8("android"))
        assertEquals("050801-beta", TurbineSdkFeatureFlagsPath.percentEncodePathSegmentUtf8("050801-beta"))
        assertEquals("aZ09-._~", TurbineSdkFeatureFlagsPath.percentEncodePathSegmentUtf8("aZ09-._~"))
    }

    @Test
    fun percentEncodeEncodesReservedAndSpace() {
        assertEquals("a%2Fb", TurbineSdkFeatureFlagsPath.percentEncodePathSegmentUtf8("a/b"))
        assertEquals("a%20b", TurbineSdkFeatureFlagsPath.percentEncodePathSegmentUtf8("a b"))
        assertEquals("caf%C3%A9", TurbineSdkFeatureFlagsPath.percentEncodePathSegmentUtf8("café"))
    }

    @Test
    fun buildGetPathMatchesTurbineRelativePath() {
        assertEquals(
            "apps/14719551-23f1-4d20-8dab-81496ffca5ea/sdk/features/android/050801-beta",
            TurbineSdkFeatureFlagsPath.buildGetPath(
                appId = "14719551-23f1-4d20-8dab-81496ffca5ea",
                platform = "android",
                sdkVersion = "050801-beta",
            ),
        )
    }
}

class FeatureFlagsJsonParserTest {
    @Test
    fun parsesFeaturesWithSiblingObjects() {
        val payload =
            """
            {
              "features": ["feature_a", "feature_b"],
              "feature_a": { "weight": 0.1 },
              "feature_b": { "enabled": true }
            }
            """.trimIndent()

        val r = FeatureFlagsJsonParser.parse(payload)
        assertEquals(listOf("feature_a", "feature_b"), r.enabledKeys)
        val meta = requireNotNull(r.metadata)
        assertTrue(meta.getValue("feature_a").toString().contains("weight"))
        assertTrue(meta.getValue("feature_b").toString().contains("enabled"))
    }

    @Test
    fun omitsMetadataWhenNoSiblingObject() {
        val r = FeatureFlagsJsonParser.parse("""{"features":["only_key"]}""")
        assertEquals(listOf("only_key"), r.enabledKeys)
        assertNull(r.metadata)
    }

    @Test
    fun normalizesIdsToLowercase() {
        val payload =
            """
            {
              "features": ["SDK_Background_Threading"],
              "sdk_background_threading": { "weight": 0.5 }
            }
            """.trimIndent()

        val r = FeatureFlagsJsonParser.parse(payload)
        assertEquals(listOf("sdk_background_threading"), r.enabledKeys)
        val weight =
            requireNotNull(r.metadata)
                .getValue("sdk_background_threading")
                .jsonObject
                .getValue("weight")
                .jsonPrimitive
                .content
        assertEquals("0.5", weight)
    }

    @Test
    fun invalidJsonReturnsEmpty() {
        assertEquals(RemoteFeatureFlagsResult.EMPTY, FeatureFlagsJsonParser.parse("{"))
    }

    @Test
    fun emptyFeaturesArrayIsSuccessfulEmpty() {
        val r = requireNotNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":[]}"""))
        assertEquals(emptyList(), r.enabledKeys)
        assertNull(r.metadata)
    }

    @Test
    fun encodeMetadataRoundTrips() {
        val payload =
            """
            {
              "features": ["feature_a"],
              "feature_a": { "weight": 0.1 }
            }
            """.trimIndent()
        val r = FeatureFlagsJsonParser.parse(payload)
        val encoded = requireNotNull(FeatureFlagsJsonParser.encodeMetadata(r.metadata))
        val map = FeatureFlagsJsonParser.parseStoredMetadataMap(encoded)
        assertTrue(map.containsKey("feature_a"))
    }
}

class FeatureFlagsClientTest {
    @Test
    fun invalidSdkVersionReturnsUnavailableWithoutHttp() =
        runTest {
            var called = false
            val client =
                FeatureFlagsClient(
                    http =
                    object : IFeatureFlagsHttp {
                        override suspend fun get(relativePath: String): FeatureFlagsHttpResponse {
                            called = true
                            error("should not be called")
                        }
                    },
                )

            val outcome =
                client.fetchRemoteFeatureFlags(
                    appId = "app",
                    platform = "android",
                    sdkVersion = "5.8.1",
                )
            assertTrue(outcome is RemoteFeatureFlagsFetchOutcome.Unavailable)
            val unavailable = outcome as RemoteFeatureFlagsFetchOutcome.Unavailable
            assertEquals(
                RemoteFeatureFlagsFetchOutcome.Unavailable.Reason.INVALID_SDK_VERSION,
                unavailable.reason,
            )
            assertTrue(!called)
        }

    @Test
    fun successParsesEmptyFeatures() =
        runTest {
            val client =
                FeatureFlagsClient(
                    http =
                    object : IFeatureFlagsHttp {
                        override suspend fun get(relativePath: String): FeatureFlagsHttpResponse {
                            assertEquals(
                                "apps/appId/sdk/features/android/050801",
                                relativePath,
                            )
                            return FeatureFlagsHttpResponse(200, """{"features":[]}""")
                        }
                    },
                )
            val outcome =
                client.fetchRemoteFeatureFlags(
                    appId = "appId",
                    platform = "android",
                    sdkVersion = "050801",
                )
            assertTrue(outcome is RemoteFeatureFlagsFetchOutcome.Success)
            assertEquals(
                emptyList(),
                (outcome as RemoteFeatureFlagsFetchOutcome.Success).result.enabledKeys,
            )
        }

    @Test
    fun unavailableIsClientErrorMatchesHttpResponse() {
        val forbidden =
            RemoteFeatureFlagsFetchOutcome.Unavailable(
                reason = RemoteFeatureFlagsFetchOutcome.Unavailable.Reason.NON_SUCCESS_HTTP,
                statusCode = 403,
            )
        assertTrue(forbidden.isClientError)
        assertTrue(FeatureFlagsHttpResponse(403, null).isClientError)

        val serverError =
            RemoteFeatureFlagsFetchOutcome.Unavailable(
                reason = RemoteFeatureFlagsFetchOutcome.Unavailable.Reason.NON_SUCCESS_HTTP,
                statusCode = 500,
            )
        assertTrue(!serverError.isClientError)
        assertTrue(!FeatureFlagsHttpResponse(500, null).isClientError)

        val noStatus =
            RemoteFeatureFlagsFetchOutcome.Unavailable(
                reason = RemoteFeatureFlagsFetchOutcome.Unavailable.Reason.INVALID_SDK_VERSION,
            )
        assertTrue(!noStatus.isClientError)
    }

    @Test
    fun clientErrorReturnsUnavailableWithSnippet() =
        runTest {
            val client =
                FeatureFlagsClient(
                    http =
                    object : IFeatureFlagsHttp {
                        override suspend fun get(relativePath: String) =
                            FeatureFlagsHttpResponse(403, """{"errors":["Forbidden"]}""")
                    },
                )
            val outcome =
                client.fetchRemoteFeatureFlags(
                    appId = "appId",
                    platform = "android",
                    sdkVersion = "050801",
                )
            assertTrue(outcome is RemoteFeatureFlagsFetchOutcome.Unavailable)
            val unavailable = outcome as RemoteFeatureFlagsFetchOutcome.Unavailable
            assertEquals(
                RemoteFeatureFlagsFetchOutcome.Unavailable.Reason.NON_SUCCESS_HTTP,
                unavailable.reason,
            )
            assertEquals(403, unavailable.statusCode)
            assertEquals("""{"errors":["Forbidden"]}""", unavailable.bodySnippet)
            assertTrue(unavailable.isClientError)
        }

    @Test
    fun emptySuccessBodyReturnsUnavailable() =
        runTest {
            val client =
                FeatureFlagsClient(
                    http =
                    object : IFeatureFlagsHttp {
                        override suspend fun get(relativePath: String) =
                            FeatureFlagsHttpResponse(200, "  ")
                    },
                )
            val outcome =
                client.fetchRemoteFeatureFlags(
                    appId = "appId",
                    platform = "android",
                    sdkVersion = "050801",
                )
            assertTrue(outcome is RemoteFeatureFlagsFetchOutcome.Unavailable)
            val unavailable = outcome as RemoteFeatureFlagsFetchOutcome.Unavailable
            assertEquals(
                RemoteFeatureFlagsFetchOutcome.Unavailable.Reason.EMPTY_BODY,
                unavailable.reason,
            )
            assertEquals(200, unavailable.statusCode)
        }

    @Test
    fun invalidJsonOnSuccessReturnsUnavailable() =
        runTest {
            var requestedPath: String? = null
            val client =
                FeatureFlagsClient(
                    http =
                    object : IFeatureFlagsHttp {
                        override suspend fun get(relativePath: String): FeatureFlagsHttpResponse {
                            requestedPath = relativePath
                            return FeatureFlagsHttpResponse(200, """{"features":"not-an-array"}""")
                        }
                    },
                )
            val outcome =
                client.fetchRemoteFeatureFlags(
                    appId = "appId",
                    platform = "ios",
                    sdkVersion = "050801",
                )
            assertTrue(outcome is RemoteFeatureFlagsFetchOutcome.Unavailable)
            val unavailable = outcome as RemoteFeatureFlagsFetchOutcome.Unavailable
            assertEquals(
                RemoteFeatureFlagsFetchOutcome.Unavailable.Reason.INVALID_JSON,
                unavailable.reason,
            )
            assertEquals("apps/appId/sdk/features/ios/050801", requestedPath)
        }
}
