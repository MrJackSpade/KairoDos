// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.util.AtomicFile
import com.mrjackspade.kairo.frontend.DocumentTreeWalker
import org.json.JSONObject
import java.io.File

/**
 * Whether each program a scan asked about is a Collection Chamber installer (DosClickteam), kept
 * by its path with its size and time, so a later scan reads only programs that are new or changed.
 */
internal class DosInstallerProbes(private val file: File) {
    private val known: MutableMap<String, String> by lazy { load() }
    private val asked = HashMap<String, String>()
    private var changed = false

    /**
     * Whether [program] is an installer, from [probe] unless it is known at its size and time.
     * A probe that fails is not kept, so the next scan asks again.
     */
    @Synchronized fun isInstaller(program: DocumentTreeWalker.FileEntry, probe: () -> Boolean): Boolean {
        val stamp = "${program.size}:${program.modified}"
        known[program.path]?.let { kept ->
            if (kept.substringBeforeLast(':') == stamp) {
                asked[program.path] = kept
                return kept.endsWith(":1")
            }
        }
        val result = probe()
        asked[program.path] = "$stamp:${if (result) 1 else 0}"
        changed = true
        return result
    }

    /** Keeps the answers of this scan, and only those: a program no longer asked about is gone. */
    @Synchronized fun save() {
        if (!changed && asked.keys == known.keys) { asked.clear(); return }
        val json = JSONObject()
        asked.forEach { (path, value) -> json.put(path, value) }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        known.clear()
        known.putAll(asked)
        asked.clear()
        changed = false
    }

    private fun load(): MutableMap<String, String> {
        val atomic = AtomicFile(file)
        if (!atomic.baseFile.isFile) return HashMap()
        val json = JSONObject(String(atomic.readFully(), Charsets.UTF_8))
        return json.keys().asSequence().associateWithTo(HashMap()) { json.getString(it) }
    }
}
