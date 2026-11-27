package dev.mobileide.data.git

/** Extracts a 0..1 fraction from Git progress lines such as "Receiving objects: 42%". */
object GitProgressParser {

    private val PERCENT = Regex("(\\d{1,3})%")

    fun parse(line: String): Float? =
        PERCENT.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)
            ?.div(100f)
}
