package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.ArtworkOverridePath

/** DOS record structure for layering, and the artwork path guard used when opening files. */
internal object DosCatalogFields {
    fun objectField(path: List<String>): Boolean =
        path == listOf("artwork") || path == listOf("launch") ||
            path == listOf("launch", "configs")

    fun safeArtPath(path: String?): String? = path?.takeIf {
        ArtworkOverridePath.valid(it, "art/catalog/dos/", asciiOnly = true,
            minLength = 17)
    }
}
