package com.mrjackspade.kairodos

import com.mrjackspade.kairo.frontend.UiText

/** Staging machine types, not Android GPU drivers. Indices are persisted: append only. */
internal object DosVideoHardware {
    const val setting = "dos_video_hardware"
    val choices = listOf(
        null to UiText.res(R.string.video_automatic),
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
    fun normalize(index: Int) = index.takeIf { it in choices.indices } ?: 0
    fun machine(index: Int) = choices[normalize(index)].first
    fun label(index: Int) = choices[normalize(index)].second
}
