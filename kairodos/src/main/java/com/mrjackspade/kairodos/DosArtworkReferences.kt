package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.JsonCopy
import org.json.JSONObject

/** Expand wire references before layering, preserving legacy paths and overrides. */
internal object DosArtworkReferences {
    fun expand(art: JSONObject): JSONObject {
        if (!art.has("id")) return art
        // A reference that does not name real artwork finds no image when that image is opened.
        val id = art.optString("id")
        val variant = art.optString("variant")
        val kinds = art.optInt("kinds")
        // LaunchBox's image for a kind, downloaded in place of the repository's copy (tools/dos_artwork.py).
        val launchbox = art.optJSONObject("launchbox")
        return JSONObject().apply {
            for ((kind, bit) in listOf("boxArt" to 1, "preview" to 2)) {
                if (kinds and bit == 0) continue
                put(kind, "art/catalog/dos/${id.take(2)}/$id/$variant/$kind.webp")
                launchbox?.optJSONObject(kind)?.let { ref ->
                    put(kind + "Url", LAUNCHBOX + (if (ref.optInt("revision") == 2) "r2_" else "") +
                        ref.optString("id") + (if (ref.optInt("format") == 1) ".png" else ".jpg"))
                }
            }
        }
    }

    private const val LAUNCHBOX = "https://images.launchbox-app.com/"
    private val launchboxImage = Regex(
        "https://images\\.launchbox-app\\.com/(?:r2_)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png)")

    fun isLaunchBoxImage(url: String) = launchboxImage.matches(url)

    /** Layering copies every value it keeps, so only the replaced fields need new objects. */
    fun record(source: JSONObject?): JSONObject? = source?.let {
        JsonCopy.shallow(it).apply {
            optJSONObject("artwork")?.let { art -> put("artwork", expand(art)) }
            optJSONObject("variants")?.let { variants ->
                put("variants", JsonCopy.shallow(variants).apply {
                    for (key in variants.keys()) {
                        variants.optJSONObject(key)?.let { value -> put(key, record(value)) }
                    }
                })
            }
        }
    }
}
