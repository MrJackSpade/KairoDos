// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * The records games' own files give them (LayeredGameCatalog's files layer), by content ID: a
 * Collection Chamber game's launch, derived from its installer's shortcuts (DosClickteamLaunch).
 * Derived once, when the game is installed or its installed archive is first scanned, and kept.
 */
class DosFilesRecords(private val file: File) {
    private val records: MutableMap<String, JSONObject> by lazy { load() }

    @Synchronized fun get(contentId: String): JSONObject? = records[contentId]

    @Synchronized fun has(contentId: String) = contentId in records

    /** Keeps [record] under every ID in [contentIds] (an installer's and the archive it installed). */
    @Synchronized fun put(contentIds: Collection<String>, record: JSONObject) {
        val text = record.toString()
        if (contentIds.all { records[it]?.toString() == text }) return
        contentIds.forEach { records[it] = JSONObject(text) }
        val json = JSONObject()
        records.forEach { (id, value) -> json.put(id, value) }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    private fun load(): MutableMap<String, JSONObject> {
        val atomic = AtomicFile(file)
        if (!atomic.baseFile.isFile) return HashMap()
        val json = JSONObject(String(atomic.readFully(), Charsets.UTF_8))
        return json.keys().asSequence().associateWithTo(HashMap()) { json.getJSONObject(it) }
    }

    companion object {
        /** A files record holding [launch]: its folder and each way it starts. */
        internal fun record(launch: DosClickteamLaunch.Launch): JSONObject = JSONObject().put(DosCatalog.LAUNCH, JSONObject()
            .put(DosCatalog.FOLDER, launch.folder)
            .put(DosCatalog.CONFIGS, JSONObject().apply { launch.configs.forEach { (name, text) -> put(name, text) } }))
    }
}
