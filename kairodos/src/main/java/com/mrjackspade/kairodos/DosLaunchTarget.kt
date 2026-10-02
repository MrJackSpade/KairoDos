package com.mrjackspade.kairodos

/** One-session launch intent. Never saved as a catalog or user startup override. */
internal sealed interface DosLaunchTarget {
    data object Normal : DosLaunchTarget
    data object Prompt : DosLaunchTarget
    data object Browse : DosLaunchTarget
    data class Program(val path: String, val occupiedDrives: Set<Char> = emptySet()) : DosLaunchTarget {
        init {
            require(path.length >= 4 && path[0] in 'A'..'Y' && path.substring(1, 3) == ":\\" &&
                path.drop(2).none { it.code < 32 || it in "\"<>|/*?:" } &&
                path.substring(3).split('\\').all { it.isNotEmpty() && it != "." && it != ".." } &&
                path.substringAfterLast('.').lowercase() in executableExtensions) { "Invalid DOS program path" }
            require(occupiedDrives.all { it in 'A'..'Z' })
        }
    }

    companion object {
        val executableExtensions = setOf("exe", "com", "bat", "cmd")
        fun batchQuote(value: String) = "\"${value.replace("%", "%%")}\""
    }
}
