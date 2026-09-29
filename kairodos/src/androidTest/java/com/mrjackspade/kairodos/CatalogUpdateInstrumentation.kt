package com.mrjackspade.kairodos

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.mrjackspade.kairo.frontend.CatalogFieldLayers
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Device fixture for partial catalog layers and rejection before snapshot activation. */
class CatalogUpdateInstrumentation : Instrumentation() {
    private var stateSlots = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        stateSlots = arguments?.getString("stateSlots") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (stateSlots) StateSlotStoreFixture.verify(targetContext.cacheDir) else verify()
            result.putString("stream", if (stateSlots) "Shared state slot transactions: OK\n"
                else "DOS catalog partial merge and invalid snapshot: OK\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "DOS catalog fixture failed: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verify() {
        val id = "sha256-dos-manifest-v1:05cc07a7f13a69cf5e086b682cbac39a9f95c9b89c25571bd5d54e349a611158"
        val shipped = targetContext.assets.open("catalog/dos/05.json").use {
            JSONObject(it.bufferedReader().readText()).getJSONObject("games").getJSONObject(id)
        }
        val partial = JSONObject().put("title", "Updated Duke")
            .put("artwork", JSONObject().put("boxArt", "art/catalog/dos/updated.webp"))
        val user = JSONObject().put("title", "My Duke")
        val merged = CatalogFieldLayers.merge(listOf(
            CatalogFieldLayers.Source("Shipped catalog", shipped),
            CatalogFieldLayers.Source("Updated catalog", partial),
            CatalogFieldLayers.Source("User override", user)
        ), DosCatalogFields::objectField, DosCatalogFields::validValue)
        check(merged.record.getString("title") == "My Duke")
        check(merged.record.getJSONObject("artwork").getString("boxArt") ==
            "art/catalog/dos/updated.webp")
        check(merged.record.getJSONObject("artwork").getString("preview") ==
            shipped.getJSONObject("artwork").getString("preview"))
        check(merged.record.getJSONObject("launch").getString("folder") ==
            shipped.getJSONObject("launch").getString("folder"))
        check(merged.record.getJSONObject("launch").getJSONObject("configs")
            .getString("dosbox.conf") == shipped.getJSONObject("launch")
            .getJSONObject("configs").getString("dosbox.conf"))
        check(merged.record.getJSONArray("tags").toString() ==
            shipped.getJSONArray("tags").toString())
        check(merged.sourceOf("title") == "User override")
        check(merged.sourceOf("artwork", "boxArt") == "Updated catalog")
        check(merged.sourceOf("artwork", "preview") == "Shipped catalog")
        val ignored = CatalogFieldLayers.merge(listOf(
            CatalogFieldLayers.Source("Shipped catalog", shipped),
            CatalogFieldLayers.Source("Invalid update", JSONObject().put("launch",
                JSONObject().put("folder", "../invalid")))
        ), DosCatalogFields::objectField, DosCatalogFields::validValue)
        check(ignored.record.getJSONObject("launch").getString("folder") ==
            shipped.getJSONObject("launch").getString("folder"))

        val validator = DosCatalogUpdate(targetContext)
        File(targetContext.filesDir, "dos-catalog-update-v1.zip").takeIf(File::isFile)
            ?.let(validator::validateFile)
        val valid = File(targetContext.cacheDir, "catalog-partial-fixture.zip")
        val invalid = File(targetContext.cacheDir, "catalog-invalid-fixture.zip")
        try {
            writeSnapshot(valid, id, partial)
            validator.validateFile(valid)
            writeSnapshot(invalid, id, JSONObject().put("launch",
                JSONObject().put("folder", "../invalid")))
            check(runCatching { validator.validateFile(invalid) }.exceptionOrNull()
                is IllegalArgumentException)
        } finally {
            valid.delete()
            invalid.delete()
        }
    }

    private fun writeSnapshot(file: File, id: String, record: JSONObject) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for (index in 0..255) {
                val name = "%02x.json".format(index)
                val games = JSONObject()
                if (index == 5) games.put(id, record)
                writeEntry(zip, name, JSONObject().put("schemaVersion", 1)
                    .put("games", games))
            }
            writeEntry(zip, "folders.json", JSONObject())
            writeEntry(zip, "controller-profiles-v1.json", JSONObject()
                .put("schemaVersion", 1).put("profiles", JSONObject()))
            writeEntry(zip, "hidden-index-v1.json", JSONObject()
                .put("schemaVersion", 1).put("hidden", JSONObject()))
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, body: JSONObject) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
