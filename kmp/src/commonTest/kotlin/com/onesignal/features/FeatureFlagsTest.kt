package com.onesignal.features

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun buildGetPathPercentEncodesAppIdPlatformAndVersion() {
        assertEquals(
            "apps/14719551-23f1-4d20-8dab-81496ffca5ea/sdk/features/android/050801-beta",
            TurbineSdkFeatureFlagsPath.buildGetPath(
                appId = "14719551-23f1-4d20-8dab-81496ffca5ea",
                platform = "android",
                sdkVersion = "050801-beta",
            ),
        )
        assertEquals(
            "apps/app%20id/sdk/features/android/050801",
            TurbineSdkFeatureFlagsPath.buildGetPath(
                appId = "app id",
                platform = "android",
                sdkVersion = "050801",
            ),
        )
    }

    @Test
    fun rejectsBlankOrPathShapingAppIds() {
        assertFalse(TurbineSdkFeatureFlagsPath.isValidAppIdSegment(""))
        assertFalse(TurbineSdkFeatureFlagsPath.isValidAppIdSegment("   "))
        assertFalse(TurbineSdkFeatureFlagsPath.isValidAppIdSegment("a/b"))
        assertFalse(TurbineSdkFeatureFlagsPath.isValidAppIdSegment("a?b"))
        assertFalse(TurbineSdkFeatureFlagsPath.isValidAppIdSegment("a#b"))
        assertTrue(TurbineSdkFeatureFlagsPath.isValidAppIdSegment("appId"))
        assertTrue(
            TurbineSdkFeatureFlagsPath.isValidAppIdSegment("14719551-23f1-4d20-8dab-81496ffca5ea"),
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
        val metaJson = requireNotNull(r.metadataJson)
        assertTrue(metaJson.contains("feature_a"))
        assertTrue(metaJson.contains("weight"))
        assertTrue(metaJson.contains("feature_b"))
    }

    @Test
    fun omitsMetadataWhenNoSiblingObject() {
        val r = FeatureFlagsJsonParser.parse("""{"features":["only_key"]}""")
        assertEquals(listOf("only_key"), r.enabledKeys)
        assertNull(r.metadataJson)
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
        val meta = requireNotNull(FeatureFlagMetadata.parse(r.metadataJson))
        assertTrue(requireNotNull(meta.jsonObjectForId("sdk_background_threading")).contains("0.5"))
    }

    @Test
    fun invalidJsonReturnsEmpty() {
        assertEquals(RemoteFeatureFlagsResult.EMPTY, FeatureFlagsJsonParser.parse("{"))
    }

    @Test
    fun emptyFeaturesArrayIsSuccessfulEmpty() {
        val r = requireNotNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":[]}"""))
        assertEquals(emptyList(), r.enabledKeys)
        assertNull(r.metadataJson)
    }

    @Test
    fun parseSuccessfulKeepsMixedArraysAndDropsInvalidElements() {
        val r =
            requireNotNull(
                FeatureFlagsJsonParser.parseSuccessful(
                    """{"features":["good", 1, null, {}, ""]}""",
                ),
            )
        assertEquals(listOf("good"), r.enabledKeys)
        assertNull(r.metadataJson)
    }

    @Test
    fun parseSuccessfulKeepsValidIdsWhenMalformedSiblingsArePresent() {
        val r =
            requireNotNull(
                FeatureFlagsJsonParser.parseSuccessful(
                    """{"features":["ok", 123, "also"]}""",
                ),
            )
        assertEquals(listOf("ok", "also"), r.enabledKeys)
    }

    @Test
    fun parseSuccessfulReturnsNullWhenNonEmptyArrayHasNoValidIds() {
        assertNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":[1,2,3]}"""))
        assertNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":[null]}"""))
        assertNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":[{}]}"""))
        assertNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":[[]]}"""))
        assertNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":[true,false]}"""))
        assertNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":[""]}"""))
        assertNull(FeatureFlagsJsonParser.parseSuccessful("""{"features":["   "]}"""))
    }

    @Test
    fun metadataJsonRoundTripsThroughStoredMap() {
        val payload =
            """
            {
              "features": ["feature_a"],
              "feature_a": { "weight": 0.1 }
            }
            """.trimIndent()
        val r = FeatureFlagsJsonParser.parse(payload)
        val meta = requireNotNull(FeatureFlagMetadata.parse(r.metadataJson))
        assertTrue(meta.ids().contains("feature_a"))
        assertTrue(requireNotNull(meta.jsonObjectForId("feature_a")).contains("weight"))
    }

    @Test
    fun metadataParseReturnsNullForBlank() {
        assertNull(FeatureFlagMetadata.parse(null))
        assertNull(FeatureFlagMetadata.parse(""))
        assertNull(FeatureFlagMetadata.parse("   "))
    }

    @Test
    fun metadataParseInvalidJsonIsEmpty() {
        val meta = requireNotNull(FeatureFlagMetadata.parse("{"))
        assertEquals(emptyList(), meta.ids())
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
            assertTrue(outcome.isUnavailable)
            assertEquals(
                RemoteFeatureFlagsUnavailableReason.INVALID_SDK_VERSION,
                outcome.reason,
            )
            assertTrue(!called)
        }

    @Test
    fun invalidAppIdReturnsUnavailableWithoutHttp() =
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
                    appId = "app/../other",
                    platform = "android",
                    sdkVersion = "050801",
                )
            assertTrue(outcome.isUnavailable)
            assertEquals(
                RemoteFeatureFlagsUnavailableReason.INVALID_APP_ID,
                outcome.reason,
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
            assertTrue(outcome.isSuccess)
            assertEquals(emptyList(), requireNotNull(outcome.result).enabledKeys)
        }

    @Test
    fun unavailableIsClientErrorMatchesHttpResponse() {
        val forbidden =
            RemoteFeatureFlagsFetchOutcome.unavailable(
                reason = RemoteFeatureFlagsUnavailableReason.NON_SUCCESS_HTTP,
                statusCode = 403,
            )
        assertTrue(forbidden.isClientError)
        assertTrue(FeatureFlagsHttpResponse(403, null).isClientError)

        val serverError =
            RemoteFeatureFlagsFetchOutcome.unavailable(
                reason = RemoteFeatureFlagsUnavailableReason.NON_SUCCESS_HTTP,
                statusCode = 500,
            )
        assertTrue(!serverError.isClientError)
        assertTrue(!FeatureFlagsHttpResponse(500, null).isClientError)

        val noStatus =
            RemoteFeatureFlagsFetchOutcome.unavailable(
                reason = RemoteFeatureFlagsUnavailableReason.INVALID_SDK_VERSION,
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
            assertTrue(outcome.isUnavailable)
            assertEquals(
                RemoteFeatureFlagsUnavailableReason.NON_SUCCESS_HTTP,
                outcome.reason,
            )
            assertEquals(403, outcome.statusCode)
            assertEquals("""{"errors":["Forbidden"]}""", outcome.bodySnippet)
            assertTrue(outcome.isClientError)
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
            assertTrue(outcome.isUnavailable)
            assertEquals(
                RemoteFeatureFlagsUnavailableReason.EMPTY_BODY,
                outcome.reason,
            )
            assertEquals(200, outcome.statusCode)
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
            assertTrue(outcome.isUnavailable)
            assertEquals(
                RemoteFeatureFlagsUnavailableReason.INVALID_JSON,
                outcome.reason,
            )
            assertEquals("apps/appId/sdk/features/ios/050801", requestedPath)
        }
}
