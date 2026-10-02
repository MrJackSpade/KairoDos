package com.mrjackspade.kairodos

import org.json.JSONObject

/** Expand wire references before layering, preserving legacy paths and overrides. */
internal object DosArtworkReferences {
    fun expand(art: JSONObject): JSONObject {
        val fields = setOf("id", "variant", "kinds")
        if (fields.none(art::has)) return art
        require(art.keys().asSequence().toSet() == fields) { "Invalid artwork fields" }
        val id = art.opt("id") as? String ?: error("Missing artwork ID")
        val variant = art.opt("variant") as? String ?: error("Missing artwork variant")
        val kinds = art.opt("kinds") as? Int ?: error("Invalid artwork kinds")
        require(id.matches(Regex("[0-9a-f]{64}")) &&
            variant.matches(Regex("[0-9a-f]{12}")) && kinds in 1..3)
        return JSONObject().apply {
            for ((kind, bit) in listOf("boxArt" to 1, "preview" to 2)) {
                if (kinds and bit != 0)
                    put(kind, "art/catalog/dos/${id.take(2)}/$id/$variant/$kind.webp")
            }
        }
    }

    fun record(source: JSONObject?): JSONObject? = source?.let {
        JSONObject(it.toString()).apply {
            optJSONObject("artwork")?.let { art -> put("artwork", expand(art)) }
            optJSONObject("variants")?.let { variants ->
                for (key in variants.keys()) {
                    variants.optJSONObject(key)?.let { value ->
                        require(!value.has("variants")) { "Nested artwork variants are unsupported" }
                        variants.put(key, record(value))
                    }
                }
            }
        }
    }
}
