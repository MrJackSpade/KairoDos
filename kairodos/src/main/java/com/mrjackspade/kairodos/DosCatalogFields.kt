package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.CatalogFieldLayers
import com.mrjackspade.kairo.frontend.ArtworkOverridePath
import org.json.JSONArray
import org.json.JSONObject

/** DOS record paths and values accepted by both layering and update validation. */
internal object DosCatalogFields {
    fun objectField(path: List<String>): Boolean =
        path == listOf("artwork") || path == listOf("launch") ||
            path == listOf("launch", "configs")

    fun validValue(path: List<String>, value: Any): Boolean = when {
        path == listOf("title") -> value is String && value.isNotBlank() && value.length <= 160
        path == listOf("description") -> value is String && value.isNotBlank() &&
            value.length <= 8000
        path == listOf("hidden") -> value is Boolean
        path == listOf("tags") -> value is JSONArray && value.length() <= 32 &&
            (0 until value.length()).all { index ->
                (value.opt(index) as? String)?.let { it.isNotBlank() && it.length <= 100 } == true
            }
        path.size == 2 && path[0] == "artwork" &&
            path[1] in setOf("boxArt", "preview") ->
            value is String && safeArtPath(value) != null
        path == listOf("launch", "folder") -> value is String && value.length in 1..128 &&
            !value.contains("..") && !value.contains('/') && !value.contains('\\')
        path == listOf("launch", "exception") -> value is Boolean
        path.size == 3 && path[0] == "launch" && path[1] == "configs" ->
            path[2].length in 1..128 && !path[2].contains("..") &&
                !path[2].contains('/') && !path[2].contains('\\') &&
                value is String && value.length <= 262144
        else -> false
    }

    fun safeArtPath(path: String?): String? = path?.takeIf {
        ArtworkOverridePath.valid(it, "art/catalog/dos/", asciiOnly = true,
            minLength = 17)
    }

    fun invalidPath(record: JSONObject): String? {
        val fields = runCatching { DosArtworkReferences.record(record)!! }
            .getOrElse { return "artwork" }.apply { remove("variants") }
        CatalogFieldLayers.invalidPath(fields, ::objectField, ::validValue)?.let {
            return it.joinToString(".")
        }
        if (!record.has("variants")) return null
        val variants = record.optJSONObject("variants") ?: return "variants"
        if (variants.length() > 64) return "variants"
        for (name in variants.keys()) {
            if (name.length !in 1..256 || name.contains('/') || name.contains('\\'))
                return "variants.$name"
            val variant = variants.optJSONObject(name) ?: return "variants.$name"
            if (variant.has("variants")) return "variants.$name.variants"
            invalidPath(variant)?.let { return "variants.$name.$it" }
        }
        return null
    }
}
