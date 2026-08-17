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
 * [RemoteFeatureFlagsResult.metadataJson].
 *
 * kotlinx.serialization types stay internal to this parser so the public surface does not
 * require the serialization dependency on host compile classpaths.
 */
object FeatureFlagsJsonParser {
    /**
     * RFC 8259–style JSON only (no lenient tokens like unquoted keys, `NaN`, trailing commas).
     */
    private val format =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            allowSpecialFloatingPointValues = false
            prettyPrint = false
        }

    private const val FEATURES_PROPERTY = "features"

    fun parse(payload: String): RemoteFeatureFlagsResult = parseSuccessful(payload) ?: RemoteFeatureFlagsResult.EMPTY

    /**
     * Parses a 200 response body. Returns `null` if the text is not JSON, not an object, does not
     * contain a `features` array, or if any array element is not a non-empty string. Returns an
     * empty result for `{"features":[]}`.
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
        if (featuresArray.isEmpty()) {
            return RemoteFeatureFlagsResult(emptyList(), null)
        }

        val flagEntries = ArrayList<Pair<String, String>>(featuresArray.size)
        for (el in featuresArray) {
            val primitive = el as? JsonPrimitive ?: return null
            if (!primitive.isString) {
                return null
            }
            val raw = primitive.content.trim()
            if (raw.isEmpty()) {
                return null
            }
            flagEntries.add(raw to canonicalizeFeatureFlagId(raw))
        }

        val distinctEntries = flagEntries.distinctBy { it.second }
        val keys = distinctEntries.map { it.second }

        val metadata =
            buildJsonObject {
                for ((rawKey, canonicalKey) in distinctEntries) {
                    findSiblingJsonObject(root, rawKey, canonicalKey)?.let { put(canonicalKey, it) }
                }
            }
        val metaOut =
            if (metadata.isEmpty()) {
                null
            } else {
                format.encodeToString(JsonElement.serializer(), metadata)
            }
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

    /**
     * Decodes a persisted metadata JSON object (flag id → object) into a map of flag id →
     * object JSON text. Non-object values are skipped. No kotlinx.serialization types on the
     * return surface.
     */
    @Suppress("ReturnCount")
    fun parseStoredMetadataMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }
        return try {
            val root = format.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            root.entries.mapNotNull { (key, value) ->
                (value as? JsonObject)?.let { key to format.encodeToString(JsonElement.serializer(), it) }
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}
