package com.mrjackspade.kairodos

import android.content.Context
import android.content.ContextWrapper
import com.mrjackspade.kairo.frontend.ControllerLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real catalog resolution with isolated downloaded snapshots, never the user's files. */
object ControllerCatalogFixture {
    fun verify(context: Context) {
        val directory = File(context.cacheDir, "controller-catalog-fixture").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
        }
        val layout = ControllerLayout.WITH_STICKS
        val duke = "sha256-dos-manifest-v1:05cc07a7f13a69cf5e086b682cbac39a9f95c9b89c25571bd5d54e349a611158"
        val bundled = JSONObject(context.assets.open("catalog/dos/controller-profiles-v1.json")
            .bufferedReader().use { it.readText() })
        val doom = bundled.getJSONObject("profiles").getJSONArray("doom-v1").getString(0)
        val archive = File(directory, "dos-catalog-update-v1.zip")
        val marker = File(directory, "dos-catalog-update-v1.zip.apk")
        try {
            val original = DosGameCatalog(isolated)
            for (id in listOf(duke, doom)) {
                check(original.gameControllerBindings(id, "renamed.zip", layout)!!
                    .single { it.input == "virtual:r2" }.keys == listOf(306))
            }
            fun snapshot(fire: Int) {
                val data = JSONObject(bundled.toString())
                for (profile in listOf("duke3d-fps-v1", "doom-v1")) {
                    val bindings = data.getJSONObject("presets").getJSONObject(profile)
                        .getJSONObject("defaults").getJSONArray("withSticks")
                    for (i in 0 until bindings.length()) {
                        val binding = bindings.getJSONObject(i)
                        if (binding.getString("input") == "virtual:r2")
                            binding.put("keys", JSONArray().put(fire))
                    }
                }
                ZipOutputStream(archive.outputStream()).use { zip ->
                    for (name in context.assets.list("catalog/dos")!!) {
                        if (!name.endsWith(".json")) continue
                        zip.putNextEntry(ZipEntry(name))
                        if (name == "controller-profiles-v1.json") zip.write(data.toString().toByteArray())
                        else {
                            // Controller-only snapshot; unrelated private bundled descriptions
                            // are intentionally not part of a public catalog fixture.
                            val empty = when (name) {
                                "folders.json" -> JSONObject()
                                "hidden-index-v1.json" -> JSONObject().put("schemaVersion", 1)
                                    .put("hidden", JSONObject())
                                else -> JSONObject().put("schemaVersion", 1).put("games", JSONObject())
                            }
                            zip.write(empty.toString().toByteArray())
                        }
                        zip.closeEntry()
                    }
                }
                DosCatalogUpdate(isolated).validateFile(archive)
                val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
                    .joinToString("") { "%02x".format(it) }
                val time = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                marker.writeText("$time:$digest")
            }
            for (fire in listOf(102, 103)) {
                snapshot(fire)
                val updated = DosGameCatalog(isolated)
                for (id in listOf(duke, doom)) {
                    val bindings = updated.gameControllerBindings(id, "renamed.zip", layout)!!
                    check(bindings.single { it.input == "virtual:r2" }.keys == listOf(fire))
                    val custom = "[{\"input\":\"virtual:r2\",\"keys\":[104]}]"
                    check(updated.gameControllerBindings(id, "renamed.zip", layout, custom)!!
                        .single().keys == listOf(104))
                    check(updated.gameControllerBindings(id, "renamed.zip", layout) == bindings)
                }
            }
        } finally { archive.delete(); marker.delete(); directory.delete() }
    }
}
