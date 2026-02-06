package cz.majny.wallet.gateway.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * A serializer for Any? that handles JSON primitives, arrays, and objects.
 * Used for JSON-RPC params/result/id fields which can be heterogeneous.
 */
object AnySerializer : KSerializer<Any?> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any")

    override fun serialize(encoder: Encoder, value: Any?) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("AnySerializer only works with JSON")

        val element = toJsonElement(value)
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Any? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("AnySerializer only works with JSON")

        val element = jsonDecoder.decodeJsonElement()
        return fromJsonElement(element)
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) ->
            k.toString() to toJsonElement(v)
        })
        is JsonElement -> value
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJsonElement(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> {
            when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.longOrNull != null -> element.long
                element.doubleOrNull != null -> element.double
                else -> element.content
            }
        }
        is JsonArray -> element.map { fromJsonElement(it) }
        is JsonObject -> element.mapValues { (_, v) -> fromJsonElement(v) }
    }
}
