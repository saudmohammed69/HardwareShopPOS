import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.hardwareshop.pos.data.sync.SyncProtocol
import com.hardwareshop.pos.data.sync.SyncWireSchema

fun main() {
    val root=JsonObject()
    root.add("entities",JsonArray().apply {
        SyncWireSchema.byName.values.forEach { spec -> add(JsonObject().apply {
            addProperty("name",spec.name);addProperty("scope",spec.scope.name)
            addProperty("immutable",spec.name in SyncProtocol.immutable)
            addProperty("ownerOnly",spec.name in SyncProtocol.ownerOnly)
            add("fields",JsonArray().apply { spec.fields.sorted().forEach { field -> add(JsonObject().apply {
                addProperty("name",field);addProperty("kind",SyncWireSchema.kind(spec.name,field))
                addProperty("required",field in spec.required);addProperty("reference",field in spec.references)
            }) } })
        }) }
    })
    println(root)
}
