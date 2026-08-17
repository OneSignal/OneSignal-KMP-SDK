package com.onesignal.features

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Strict JSON parsing for the Turbine SDK feature-flags response.
 *
 * Wire shape: root object with a `features` array of string flag ids. Optional per-flag JSON
 * objects may appear as sibling root properties (same name as the id); those are merged into
 * [RemoteFeatureFlagsResult.metadata].
 */
object FeatureFlagsJsonParser {
    /**
     * RFC 8259–style JSON only (no lenient tokens like unquoted keys, `NaN`, trailing commas).
     */
    val format =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            allowSpecialFloatingPointValues = false
            prettyPrint = false
        }

    private const val FEATURES_PROPERTY = "features"

    fun parse(payload: String): RemoteFeatureFlagsResult = parseSuccessful(payload) ?: RemoteFeatureFlagsResult.EMPTY

    /**
     * Parses a 200 response body. Returns `null` if the text is not JSON, not an object, or does not
     * contain a `features` array of the expected element types. Returns an empty result for
     * `{"features":[]}`.
     */
    fun parseSuccessful(payload: String): RemoteFeatureFlagsResult? {
        return try {
            val root = format.parseToJsonElement(payload) as? JsonObject ?: return null
            parseRootStrict(root)
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("ReturnCount")
    private fun parseRootStrict(root: JsonObject): RemoteFeatureFlagsResult? {
        val featuresEl = root[FEATURES_PROPERTY] ?: return null
        val featuresArray = featuresEl as? JsonArray ?: return null
        val flagEntries =
            featuresArray.mapNotNull { el ->
                (el as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { raw -> raw to canonicalizeFeatureFlagId(raw) }
            }.distinctBy { it.second }

        if (flagEntries.isEmpty()) {
            // `[]` is an authoritative empty config; a non-empty array that filtered down
            // to empty is a contract violation. Null surfaces as Unavailable upstream so
            // callers preserve the cached list instead of overwriting it with [].
            return if (featuresArray.isEmpty()) RemoteFeatureFlagsResult(emptyList(), null) else null
        }

        val keys = flagEntries.map { it.second }

        val metadata =
            buildJsonObject {
                for ((rawKey, canonicalKey) in flagEntries) {
                    findSiblingJsonObject(root, rawKey, canonicalKey)?.let { put(canonicalKey, it) }
                }
            }
        val metaOut = if (metadata.isEmpty()) null else metadata
        return RemoteFeatureFlagsResult(keys, metaOut)
    }

    @Suppress("ReturnCount")
    private fun findSiblingJsonObject(
        root: JsonObject,
        rawKeyFromFeaturesArray: String,
        canonicalKey: String,
    ): JsonObject? {
        for (candidate in listOf(rawKeyFromFeaturesArray, canonicalKey)) {
            if (candidate == FEATURES_PROPERTY) {
                continue
            }
            when (val v = root[candidate]) {
                is JsonObject -> return v
                else -> Unit
            }
        }
        for ((k, v) in root) {
            if (k == FEATURES_PROPERTY) {
                continue
            }
            if (k.equals(rawKeyFromFeaturesArray, ignoreCase = true) && v is JsonObject) {
                return v
            }
        }
        return null
    }

    fun encodeMetadata(metadata: JsonObject?): String? =
        metadata?.let { format.encodeToString(JsonElement.serializer(), it) }

    /**
     * Decodes a persisted metadata JSON object (flag id → object) into a map.
     * Non-object values are skipped.
     */
    @Suppress("ReturnCount")
    fun parseStoredMetadataMap(raw: String?): Map<String, JsonObject> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }
        return try {
            val root = format.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            root.entries.mapNotNull { (key, value) ->
                (value as? JsonObject)?.let { key to it }
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}
