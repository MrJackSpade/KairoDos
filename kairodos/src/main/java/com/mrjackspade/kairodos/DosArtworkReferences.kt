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
        return JSONObject().apply {
            for ((kind, bit) in listOf("boxArt" to 1, "preview" to 2)) {
                if (kinds and bit != 0)
                    put(kind, "art/catalog/dos/${id.take(2)}/$id/$variant/$kind.webp")
            }
        }
    }

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
