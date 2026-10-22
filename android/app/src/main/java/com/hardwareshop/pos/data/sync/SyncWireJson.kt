package com.hardwareshop.pos.data.sync

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Explicit JSON codec: no reflective Kotlin/Gson models or Double conversion of paisa. */
object SyncWireJson {
    fun encode(m: SyncProtocol.Mutation): String {
        SyncProtocol.validate(m)
        return JsonObject().apply {
            addProperty("operationId", m.operationId); addProperty("recordId", m.recordId)
            addProperty("entity", m.entity); addProperty("scope", m.scope.name)
            addProperty("authorUid", m.authorUid); addProperty("baseVersion", m.baseVersion)
            addProperty("deleted", m.deleted)
            add("fields", JsonObject().apply {
                m.fields.toSortedMap().forEach { (key, value) ->
                    when (value) {
                        null -> add(key, JsonNull.INSTANCE)
                        is String -> addProperty(key, value)
                        is Boolean -> addProperty(key, value)
                        is Number -> addProperty(key, value)
                        else -> error("Unsupported wire field")
                    }
                }
            })
        }.toString()
    }

    fun decode(json: String): SyncProtocol.Mutation {
        require(json.toByteArray(Charsets.UTF_8).size <= 900_000)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.keySet() == setOf("operationId","recordId","entity","scope","authorUid","baseVersion","deleted","fields"))
        require(root.get("deleted").isJsonPrimitive && root.get("deleted").asJsonPrimitive.isBoolean)
        require(root.get("baseVersion").isJsonPrimitive && root.get("baseVersion").asJsonPrimitive.isNumber)
        fun string(key: String): String = root.get(key).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
        val fields = root.getAsJsonObject("fields").entrySet().associate { (key, value) ->
            key to when {
                value.isJsonNull -> null
                !value.isJsonPrimitive -> error("Nested fields are not part of the sync schema")
                value.asJsonPrimitive.isString -> value.asString
                value.asJsonPrimitive.isBoolean -> value.asBoolean
                value.asString.any { it == '.' || it == 'e' || it == 'E' } -> value.asDouble
                else -> value.asBigDecimal.longValueExact()
            }
        }
        return SyncProtocol.Mutation(string("operationId"),string("recordId"),string("entity"),
            SyncProtocol.Scope.valueOf(string("scope")),string("authorUid"),
            root.get("baseVersion").asBigDecimal.longValueExact(),root.get("deleted").asBoolean,fields
        ).also { SyncProtocol.validate(it) }.let(SyncProtocol::canonical)
    }
}
