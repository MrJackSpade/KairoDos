package com.mrjackspade.kairodos

/** Staging machine types, not Android GPU drivers. Indices are persisted: append only. */
internal object DosVideoHardware {
    const val setting = "dos_video_hardware"
    val choices = listOf(
        null to "Automatic (game profile)",
        "svga_s3" to "S3 Trio64 (VGA / SVGA)",
        "svga_et4000" to "Tseng ET4000",
        "svga_et3000" to "Tseng ET3000",
        "svga_paradise" to "Paradise PVGA1A",
        "vesa_nolfb" to "S3 (no linear framebuffer)",
        "vesa_oldvbe" to "S3 (VESA 1.2)",
        "ega" to "EGA",
        "cga" to "CGA",
        "cga_mono" to "CGA monochrome",
        "hercules" to "Hercules",
        "tandy" to "Tandy",
        "pcjr" to "IBM PCjr"
    )
    fun normalize(index: Int) = index.takeIf { it in choices.indices } ?: 0
    fun machine(index: Int) = choices[normalize(index)].first
    fun label(index: Int) = choices[normalize(index)].second
}
