// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Installs the pinned engine's freely licensed keyboard/mapping resources.
 * Runs on the preparation worker; the marker avoids copying on every launch. */
internal object DosStagingResources {
    fun prepare(context: Context, cancelled: AtomicBoolean): File {
        val root = File(context.filesDir, "system/staging-0.83.0")
        val ready = File(root, "ready-v1")
        if (ready.isFile) return root
        root.mkdirs()
        fun copy(asset: String, output: File) {
            require(!cancelled.get()) { "Cancelled" }
            val children = context.assets.list(asset).orEmpty()
            if (children.isNotEmpty()) {
                output.mkdirs()
                for (child in children) copy("$asset/$child", File(output, child))
            } else {
                output.parentFile!!.mkdirs()
                context.assets.open(asset).use { input -> output.outputStream().use { input.copyTo(it) } }
            }
        }
        copy("staging-resources", File(root, "resources"))
        ready.writeText("7b40053b7ac580843d0461eba8c36a47a990e66c\n")
        return root
    }
}
