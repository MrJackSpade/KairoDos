package com.mrjackspade.kairodos

import android.system.Os
import com.mrjackspade.kairo.frontend.CatalogFieldLayers
import com.mrjackspade.kairo.frontend.GameMetadataOverrides
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real file transactions in a disposable cache directory; never touches user metadata. */
object CatalogOverrideFixture {
    fun verify(cache: File) {
        val root = File(cache, "catalog-override-transaction-fixture")
        root.deleteRecursively()
        check(root.mkdirs())
        try {
            val id = "sha256-dos-manifest-v1:" + "0".repeat(64)
            val file = File(root, "flat.json")
            val flat = GameMetadataOverrides(file, validRecord = { key, record ->
                key == id && DosCatalogFields.invalidPath(record) == null
            })
            flat.set(id, "title", "My title")
            flat.setSubfield(id, "artwork", "boxArt", "art/catalog/dos/test.webp")
            val committed = file.readBytes()
            check(!JSONObject(file.readText()).has("games"))
            check(runCatching { flat.set(id, "title", 99) }.isFailure)
            check(file.readBytes().contentEquals(committed))
            val copy = flat.record(id)!!
            copy.put("title", "Changed outside store")
            check(flat.record(id)!!.getString("title") == "My title")
            val tags = JSONArray().put("original")
            flat.set(id, "tags", tags)
            tags.put("changed after commit")
            check(flat.record(id)!!.getJSONArray("tags").length() == 1)
            val beforeFailure = file.readBytes()
            Os.chmod(root.absolutePath, 0b101101101)
            try {
                check(runCatching { flat.set(id, "title", "Failed write") }.isFailure)
                check(file.readBytes().contentEquals(beforeFailure))
                check(flat.record(id)!!.getString("title") == "My title")
            } finally { Os.chmod(root.absolutePath, 0b111000000) }
            flat.clear(id, "artwork")
            check(flat.record(id)!!.has("title") && !flat.record(id)!!.has("artwork"))
            flat.clear(id)
            check(flat.record(id) == null && JSONObject(file.readText()).length() == 0)

            val boundedFile = File(root, "bounded.json")
            val bounded = GameMetadataOverrides(boundedFile, 128)
            bounded.set("id", "title", "Previous")
            val boundedBytes = boundedFile.readBytes()
            check(runCatching { bounded.set("id", "title", "x".repeat(200)) }.isFailure)
            check(boundedFile.readBytes().contentEquals(boundedBytes))
            boundedFile.writeText("{broken")
            check(runCatching { bounded.set("id", "title", "Overwrite corruption") }.isFailure)
            check(boundedFile.readText() == "{broken")
            check(bounded.record("id")!!.getString("title") == "Previous")

            val envelope = File(root, "schema.json")
            fun validRecord(key: String, record: JSONObject) = key == "pc98" &&
                record.keys().asSequence().all { field -> when (field) {
                    "title" -> record.opt(field) is String
                    "machine" -> record.optJSONObject(field)?.let { machine ->
                        machine.keys().asSequence().all { machine.opt(it) is Int }
                    } == true
                    else -> false
                } }
            val schema = GameMetadataOverrides(envelope, schemaEnvelope = true, validRecord = ::validRecord)
            schema.set("pc98", "title", "PC-98 title")
            schema.updateSubfields("pc98", "machine", mapOf("clock" to 25, "multiple" to 20))
            val parsed = JSONObject(envelope.readText())
            check(parsed.getInt("schemaVersion") == 1 && parsed.getJSONObject("games").has("pc98"))
            val original = envelope.readBytes()
            check(runCatching { schema.updateSubfields("pc98", "machine", mapOf("clock" to "bad")) }.isFailure)
            check(envelope.readBytes().contentEquals(original))
            schema.updateSubfields("pc98", "machine", mapOf("clock" to null))
            check(schema.record("pc98")!!.getJSONObject("machine").getInt("multiple") == 20)
            schema.clear("pc98")
            check(JSONObject(envelope.readText()).getJSONObject("games").length() == 0)
            envelope.writeText("{\"schemaVersion\":2,\"games\":{}}")
            val future = GameMetadataOverrides(envelope, schemaEnvelope = true, validRecord = ::validRecord)
            check(runCatching { future.set("pc98", "title", "Wrong schema") }.isFailure)
            check(JSONObject(envelope.readText()).getInt("schemaVersion") == 2)
            File(envelope.path + ".bak").writeBytes(original)
            val recovered = GameMetadataOverrides(envelope, schemaEnvelope = true, validRecord = ::validRecord)
            check(recovered.record("pc98")!!.getString("title") == "PC-98 title")
            recovered.set("pc98", "title", "Recovered")
            check(!File(envelope.path + ".bak").exists())
            verifyLayers()
        } finally {
            Os.chmod(root.absolutePath, 0b111000000)
            root.deleteRecursively()
        }
    }

    private fun verifyLayers() {
        val shipped = JSONObject().put("title", "Shipped").put("tags", JSONArray().put("base"))
            .put("launch", JSONObject().put("commands", JSONArray().put("START")).put("timeoutMs", 30000))
        val updated = JSONObject().put("title", "Updated")
            .put("launch", JSONObject().put("text", "RUN"))
        val invalid = JSONObject().put("title", 99)
            .put("launch", JSONObject().put("text", 99).put("timeoutMs", 60000))
        val user = JSONObject().put("title", "Mine")
            .put("launch", JSONObject().put("timeoutMs", 45000))
        fun valid(path: List<String>, value: Any): Boolean = when (path) {
            listOf("title"), listOf("launch", "text") -> value is String
            listOf("tags"), listOf("launch", "commands") -> value is JSONArray
            listOf("launch", "timeoutMs") -> value is Int
            else -> false
        }
        fun merge(last: JSONObject?) = CatalogFieldLayers.merge(listOf(
            CatalogFieldLayers.Source("Shipped", shipped), CatalogFieldLayers.Source("Updated", updated),
            CatalogFieldLayers.Source("Invalid", invalid), CatalogFieldLayers.Source("Override", last)
        ), { it == listOf("launch") }, ::valid,
            { path, record -> path == listOf("launch") && !(record.has("text") && record.has("commands")) &&
                record.keys().asSequence().all { valid(path + it, record.get(it)) } },
            { _, record -> if (record.has("text")) setOf("commands") else emptySet() })
        val defaults = merge(null)
        check(defaults.record.getString("title") == "Updated" && defaults.sourceOf("title") == "Updated")
        check(defaults.record.getJSONObject("launch").getInt("timeoutMs") == 30000)
        check(defaults.sourceOf("launch", "text") == "Updated")
        check(defaults.sourceOf("launch", "commands") == null && defaults.sourceIndexOf("launch", "commands") == null)
        check(defaults.sourceIndexOf("launch", "text") == 1)
        val custom = merge(user)
        check(custom.record.getString("title") == "Mine" && custom.sourceOf("title") == "Override")
        check(custom.sourceOf("launch") == "Override" && custom.sourceOf("launch", "text") == "Updated")
        check(custom.record.getJSONObject("launch").getInt("timeoutMs") == 45000)
        custom.record.getJSONArray("tags").put("mutated result")
        check(shipped.getJSONArray("tags").length() == 1)
    }
}
