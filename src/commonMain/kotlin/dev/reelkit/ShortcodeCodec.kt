package dev.reelkit

internal object ShortcodeCodec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val URL_REGEX = Regex("""instagram\.com(?:/(?!share/)[^/?#]+)?/(?:p|tv|reels?)/([^/?#&]+)""")

    fun shortcodeFromUrl(url: String): String? = URL_REGEX.find(url)?.groupValues?.get(1)

    /**
     * Instagram shortcodes are the media id in base 64; "long" private shortcodes carry 28 extra chars.
     * The id can exceed 63 bits, so convert with decimal digit arithmetic instead of a Long.
     */
    fun toMediaId(shortcode: String): String {
        val core = if (shortcode.length > 28) shortcode.dropLast(28) else shortcode
        val digits = mutableListOf(0) // little-endian decimal digits
        for (c in core) {
            val d = ALPHABET.indexOf(c)
            require(d >= 0) { "Invalid shortcode character '$c'" }
            var carry = d
            for (i in digits.indices) {
                val v = digits[i] * 64 + carry
                digits[i] = v % 10
                carry = v / 10
            }
            while (carry > 0) { digits.add(carry % 10); carry /= 10 }
        }
        return digits.asReversed().joinToString("")
    }
}
