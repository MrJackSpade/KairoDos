package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.UiText

/** Staging machine types, not Android GPU drivers: each choice by DOSBox's machine name (DosCatalog.video). */
internal object DosVideoHardware {
    val choices = listOf(
        DosCatalog.AUTO to UiText.res(R.string.video_automatic),
        "svga_s3" to UiText.fixed("S3 Trio64 (VGA / SVGA)"),
        "svga_et4000" to UiText.fixed("Tseng ET4000"),
        "svga_et3000" to UiText.fixed("Tseng ET3000"),
        "svga_paradise" to UiText.fixed("Paradise PVGA1A"),
        "vesa_nolfb" to UiText.res(R.string.video_s3_no_lfb),
        "vesa_oldvbe" to UiText.fixed("S3 (VESA 1.2)"),
        "ega" to UiText.fixed("EGA"),
        "cga" to UiText.fixed("CGA"),
        "cga_mono" to UiText.res(R.string.video_cga_mono),
        "hercules" to UiText.fixed("Hercules"),
        "tandy" to UiText.fixed("Tandy"),
        "pcjr" to UiText.fixed("IBM PCjr")
    )

    /** The machine DOSBox runs for [id]; null for Automatic, which keeps the configuration's. */
    fun machine(id: String): String? = id.takeIf { it != DosCatalog.AUTO }

    /**
     * The choice KairoDos stored by its place in [choices] before games' settings were catalog
     * overrides (DosSettingsMigration); an unknown place is Automatic, as it was read then.
     */
    fun fromIndex(index: Int): String = choices.getOrNull(index)?.first ?: DosCatalog.AUTO
}
