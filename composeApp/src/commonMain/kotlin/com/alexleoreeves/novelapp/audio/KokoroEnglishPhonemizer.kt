package com.alexleoreeves.novelapp.audio

private const val MAX_KOKORO_TOKENS = 510

data class KokoroPhonemization(
    val phonemes: String,
    val tokenIds: IntArray,
    val unknownWords: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KokoroPhonemization) return false
        return phonemes == other.phonemes &&
            tokenIds.contentEquals(other.tokenIds) &&
            unknownWords == other.unknownWords
    }

    override fun hashCode(): Int {
        var result = phonemes.hashCode()
        result = 31 * result + tokenIds.contentHashCode()
        result = 31 * result + unknownWords.hashCode()
        return result
    }
}

object KokoroEnglishPhonemizer {
    fun parseCmuDictionary(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        raw.lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() && !it.startsWith(";;;") }
            .forEach { line ->
                val parts = line.split(Regex("""\s+"""))
                if (parts.size < 2) return@forEach
                val word = parts.first()
                    .replace(Regex("""\(\d+\)$"""), "")
                    .lowercase()
                    .replace("’", "'")
                if (word.any { it.isLetter() } && word !in result) {
                    result[word] = parts.drop(1).joinToString(" ")
                }
            }
        return result
    }

    fun phonemizeToTokens(
        text: String,
        dictionary: Map<String, String>
    ): KokoroPhonemization? {
        val tokens = tokenizeText(expandContractions(text))
        if (tokens.isEmpty()) return null

        val phonemeParts = mutableListOf<String>()
        val unknownWords = mutableListOf<String>()
        var previousWasWord = false

        for (token in tokens) {
            val part = when {
                token.isSupportedPunctuation() -> token
                token.all { it.isDigit() } -> wordsForNumber(token).mapNotNull { word ->
                    dictionary[word]?.arpabetToKokoro()
                }.joinToString(" ")
                else -> {
                    val normalized = token.normalizeWord()
                    val arpabet = dictionary[normalized] ?: dictionary[normalized.removeSuffix("'s")]
                    if (arpabet != null) {
                        arpabet.arpabetToKokoro()
                    } else {
                        unknownWords += token
                        ruleBasedFallback(normalized)
                    }
                }
            }
            if (part.isBlank()) continue
            val shouldSpace = previousWasWord && !part.isSupportedPunctuation()
            if (shouldSpace) phonemeParts += " "
            phonemeParts += part
            previousWasWord = !part.isSupportedPunctuation()
        }

        val phonemes = phonemeParts.joinToString("")
            .replace(Regex("""\s+([,.;:!?])"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (phonemes.isBlank()) return null
        val tokenList = phonemes.map { char -> tokenIds[char] ?: return null }
        val tokenIds = tokenList.toIntArray()
        if (tokenIds.isEmpty() || tokenIds.size > MAX_KOKORO_TOKENS) return null

        val wordCount = tokens.count { it.any(Char::isLetter) }.coerceAtLeast(1)
        if (unknownWords.size > maxOf(5, wordCount / 2)) {
            println("[KokoroPhonemizer] Too many unknown words: ${unknownWords.size} of $wordCount (${unknownWords.take(10).joinToString(", ")})")
            return null
        }
        if (unknownWords.isNotEmpty()) {
            println("[KokoroPhonemizer] Unknown words (but within limit): ${unknownWords.take(8).joinToString(", ")}")
        }

        return KokoroPhonemization(
            phonemes = phonemes,
            tokenIds = tokenIds,
            unknownWords = unknownWords.distinct().take(8)
        )
    }
}

private fun tokenizeText(text: String): List<String> =
    Regex("""[A-Za-z][A-Za-z'’.-]*|\d+|[;:,.!?—…"“”()]""")
        .findAll(text)
        .map { it.value.trim() }
        .filter { it.isNotBlank() }
        .toList()

private fun expandContractions(text: String): String {
    var expanded = text
    contractionExpansions.forEach { (pattern, replacement) ->
        expanded = expanded.replace(Regex(pattern, RegexOption.IGNORE_CASE), replacement)
    }
    return expanded
}

private fun String.normalizeWord(): String =
    lowercase()
        .replace("’", "'")
        .trim('.', '-', '_')

private fun String.isSupportedPunctuation(): Boolean =
    length == 1 && first() in supportedPunctuation

private fun String.arpabetToKokoro(): String {
    val parts = split(Regex("""\s+""")).filter { it.isNotBlank() }
    return buildString {
        for (part in parts) {
            val stress = part.lastOrNull()?.takeIf { it.isDigit() }
            val base = if (stress != null) part.dropLast(1) else part
            val phoneme = arpabetToIpa[base] ?: continue
            if (stress == '1') append('ˈ')
            if (stress == '2') append('ˌ')
            append(phoneme)
        }
    }
}

private fun ruleBasedFallback(word: String): String {
    if (word.isBlank()) return ""
    val lower = word.filter { it.isLetter() }.lowercase()
    if (lower.isBlank()) return ""
    val output = StringBuilder()
    var index = 0
    var stressed = false
    while (index < lower.length) {
        val remaining = lower.substring(index)
        val match = spellingRules.firstOrNull { (pattern, _) -> remaining.startsWith(pattern) }
        val piece = if (match != null) {
            index += match.first.length
            match.second
        } else {
            val mapped = letterFallback[lower[index]] ?: ""
            index++
            mapped
        }
        if (!stressed && piece.any { it in fallbackVowels }) {
            output.append('ˈ')
            stressed = true
        }
        output.append(piece)
    }
    return output.toString()
}

private fun wordsForNumber(raw: String): List<String> {
    val value = raw.toIntOrNull() ?: return raw.map { digitWords[it].orEmpty() }.filter { it.isNotBlank() }
    if (value == 0) return listOf("zero")
    if (value < 0 || value > 9999) return raw.map { digitWords[it].orEmpty() }.filter { it.isNotBlank() }

    fun underHundred(n: Int): List<String> = when {
        n < 20 -> listOf(numberWords[n].orEmpty())
        n % 10 == 0 -> listOf(tensWords[n].orEmpty())
        else -> listOf(tensWords[(n / 10) * 10].orEmpty(), numberWords[n % 10].orEmpty())
    }.filter { it.isNotBlank() }

    fun underThousand(n: Int): List<String> {
        if (n < 100) return underHundred(n)
        return buildList {
            add(numberWords[n / 100].orEmpty())
            add("hundred")
            if (n % 100 != 0) addAll(underHundred(n % 100))
        }.filter { it.isNotBlank() }
    }

    return buildList {
        if (value >= 1000) {
            add(numberWords[value / 1000].orEmpty())
            add("thousand")
        }
        val rest = value % 1000
        if (rest != 0) addAll(underThousand(rest))
    }.filter { it.isNotBlank() }
}

private val tokenIds: Map<Char, Int> = mapOf(
    '$' to 0, ';' to 1, ':' to 2, ',' to 3, '.' to 4, '!' to 5, '?' to 6,
    '—' to 9, '…' to 10, '"' to 11, '(' to 12, ')' to 13, '“' to 14, '”' to 15,
    ' ' to 16, '\u0303' to 17, 'ʣ' to 18, 'ʥ' to 19, 'ʦ' to 20, 'ʨ' to 21,
    'ᵝ' to 22, 'ꭧ' to 23, 'A' to 24, 'I' to 25, 'O' to 31, 'Q' to 33,
    'S' to 35, 'T' to 36, 'W' to 39, 'Y' to 41, 'ᵊ' to 42, 'a' to 43,
    'b' to 44, 'c' to 45, 'd' to 46, 'e' to 47, 'f' to 48, 'h' to 50,
    'i' to 51, 'j' to 52, 'k' to 53, 'l' to 54, 'm' to 55, 'n' to 56,
    'o' to 57, 'p' to 58, 'q' to 59, 'r' to 60, 's' to 61, 't' to 62,
    'u' to 63, 'v' to 64, 'w' to 65, 'x' to 66, 'y' to 67, 'z' to 68,
    'ɑ' to 69, 'ɐ' to 70, 'ɒ' to 71, 'æ' to 72, 'β' to 75, 'ɔ' to 76,
    'ɕ' to 77, 'ç' to 78, 'ɖ' to 80, 'ð' to 81, 'ʤ' to 82, 'ə' to 83,
    'ɚ' to 85, 'ɛ' to 86, 'ɜ' to 87, 'ɟ' to 90, 'ɡ' to 92,
    'ɥ' to 99, 'ɨ' to 101, 'ɪ' to 102, 'ʝ' to 103,
    'ɯ' to 110, 'ɰ' to 111, 'ŋ' to 112, 'ɳ' to 113, 'ɲ' to 114, 'ɴ' to 115,
    'ø' to 116, 'ɸ' to 118, 'θ' to 119, 'œ' to 120,
    'ɹ' to 123, 'ɾ' to 125, 'ɻ' to 126, 'ʁ' to 128, 'ɽ' to 129, 'ʂ' to 130,
    'ʃ' to 131, 'ʈ' to 132, 'ʧ' to 133, 'ʊ' to 135, 'ʋ' to 136, 'ʌ' to 138,
    'ɣ' to 139, 'ɤ' to 140, 'χ' to 142, 'ʒ' to 147, 'ʔ' to 148,
    'ˈ' to 156, 'ˌ' to 157, 'ː' to 158, 'ʰ' to 162, 'ʲ' to 164,
    '↓' to 169, '→' to 171, '↗' to 172, '↘' to 173, 'ᵻ' to 177
)

private val supportedPunctuation = setOf(';', ':', ',', '.', '!', '?', '—', '…', '"', '“', '”', '(', ')')

private val arpabetToIpa = mapOf(
    "AA" to "ɑ", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔ", "AW" to "aʊ",
    "AY" to "aɪ", "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð",
    "EH" to "ɛ", "ER" to "ɚ", "EY" to "eɪ", "F" to "f", "G" to "ɡ",
    "HH" to "h", "IH" to "ɪ", "IY" to "iː", "JH" to "ʤ", "K" to "k",
    "L" to "l", "M" to "m", "N" to "n", "NG" to "ŋ", "OW" to "oʊ",
    "OY" to "ɔɪ", "P" to "p", "R" to "ɹ", "S" to "s", "SH" to "ʃ",
    "T" to "t", "TH" to "θ", "UH" to "ʊ", "UW" to "uː", "V" to "v",
    "W" to "w", "Y" to "j", "Z" to "z", "ZH" to "ʒ"
)

private val spellingRules = listOf(
    "tion" to "ʃən", "sion" to "ʒən", "ough" to "oʊ", "augh" to "ɔ",
    "eigh" to "eɪ", "igh" to "aɪ", "ch" to "tʃ", "sh" to "ʃ", "th" to "θ",
    "ph" to "f", "ng" to "ŋ", "qu" to "kw", "ck" to "k", "wh" to "w",
    "oo" to "uː", "ee" to "iː", "ea" to "iː", "ai" to "eɪ", "ay" to "eɪ",
    "oi" to "ɔɪ", "oy" to "ɔɪ", "ow" to "aʊ", "ou" to "aʊ"
)

private val letterFallback = mapOf(
    'a' to "æ", 'b' to "b", 'c' to "k", 'd' to "d", 'e' to "ɛ",
    'f' to "f", 'g' to "ɡ", 'h' to "h", 'i' to "ɪ", 'j' to "ʤ",
    'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n", 'o' to "ɑ",
    'p' to "p", 'q' to "k", 'r' to "ɹ", 's' to "s", 't' to "t",
    'u' to "ʌ", 'v' to "v", 'w' to "w", 'x' to "ks", 'y' to "i",
    'z' to "z"
)

private val fallbackVowels = setOf('ɑ', 'æ', 'ʌ', 'ɔ', 'a', 'ʊ', 'ɪ', 'i', 'ɛ', 'ɚ', 'e', 'o', 'u')

private val contractionExpansions = listOf(
    """\bcan't\b""" to "can not",
    """\bwon't\b""" to "will not",
    """\bshan't\b""" to "shall not",
    """n't\b""" to " not",
    """'re\b""" to " are",
    """'ve\b""" to " have",
    """'ll\b""" to " will",
    """'d\b""" to " would",
    """'m\b""" to " am",
    """\bit's\b""" to "it is",
    """\bthat's\b""" to "that is",
    """\bthere's\b""" to "there is"
)

private val numberWords = mapOf(
    0 to "zero", 1 to "one", 2 to "two", 3 to "three", 4 to "four",
    5 to "five", 6 to "six", 7 to "seven", 8 to "eight", 9 to "nine",
    10 to "ten", 11 to "eleven", 12 to "twelve", 13 to "thirteen",
    14 to "fourteen", 15 to "fifteen", 16 to "sixteen", 17 to "seventeen",
    18 to "eighteen", 19 to "nineteen"
)

private val tensWords = mapOf(
    20 to "twenty", 30 to "thirty", 40 to "forty", 50 to "fifty",
    60 to "sixty", 70 to "seventy", 80 to "eighty", 90 to "ninety"
)

private val digitWords = mapOf(
    '0' to "zero", '1' to "one", '2' to "two", '3' to "three", '4' to "four",
    '5' to "five", '6' to "six", '7' to "seven", '8' to "eight", '9' to "nine"
)
