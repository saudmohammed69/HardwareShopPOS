package com.hardwareshop.pos.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Manifest contains references to immutable row receipts, not entire database tables. */
object SyncBundleJson {
    // Compact typed receipt references, not JSON business payloads. Their complete
    // grammar can be validated by Firestore rules without unbounded list iteration.
    fun wireMembers(m: SyncFinancialBundle.Manifest): List<String> = m.members.map {
        "${it.operationId}:${it.recordId}:${it.entity}:${it.version}"
    }
    fun readWireMembers(values: List<*>): List<SyncFinancialBundle.Member> = values.map {
        require(it is String)
        val parts=it.split(':')
        require(parts.size==4)
        SyncFinancialBundle.Member(parts[0],parts[1],parts[2],parts[3].toLong())
    }
    fun encode(m: SyncFinancialBundle.Manifest): String = JsonObject().apply {
        addProperty("bundleId",m.bundleId);addProperty("rootId",m.rootId)
        addProperty("rootEntity",m.rootEntity);addProperty("scope",m.scope.name)
        add("members",JsonArray().apply { m.members.forEach { member -> add(JsonObject().apply {
            addProperty("operationId",member.operationId);addProperty("recordId",member.recordId)
            addProperty("entity",member.entity);addProperty("version",member.version)
        }) } })
    }.toString()

    fun decode(json: String): SyncFinancialBundle.Manifest {
        require(json.toByteArray(Charsets.UTF_8).size<=700_000)
        val o=JsonParser.parseString(json).asJsonObject
        require(o.keySet()==setOf("bundleId","rootId","rootEntity","scope","members"))
        fun string(obj: JsonObject,key: String)=obj.get(key).also {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
        }.asString
        val members=o.getAsJsonArray("members")
        require(members.size() in 2..2000)
        val result=SyncFinancialBundle.Manifest(string(o,"bundleId"),string(o,"rootId"),string(o,"rootEntity"),
            SyncProtocol.Scope.valueOf(string(o,"scope")),members.map { raw ->
                val row=raw.asJsonObject
                require(row.keySet()==setOf("operationId","recordId","entity","version"))
                require(row.get("version").asJsonPrimitive.isNumber)
                SyncFinancialBundle.Member(string(row,"operationId"),string(row,"recordId"),string(row,"entity"),
                    row.get("version").asBigDecimal.longValueExact().also { require(it>0) })
            })
        val pattern=Regex("[A-Za-z0-9_-]{16,120}")
        require(pattern.matches(result.bundleId) && pattern.matches(result.rootId))
        require(result.rootEntity in setOf("bills","purchase_bills"))
        require(result.members.all { pattern.matches(it.operationId) && pattern.matches(it.recordId) && it.entity in SyncFinancialBundle.entities })
        require(result.members.map { it.operationId }.distinct().size==result.members.size)
        require(result.members.map { it.recordId }.distinct().size==result.members.size)
        require(result.members.count { it.recordId==result.rootId && it.entity==result.rootEntity && it.operationId==result.bundleId }==1)
        return result
    }
}
