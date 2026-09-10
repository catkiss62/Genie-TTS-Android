package com.catkiss62.geniettsbenchmark

/** IDs from Genie-TTS v2 SymbolsV2.py. Keep this table aligned with the converted V2 model. */
object GenieSymbolsV2 {
    val id: Map<String, Long> = mapOf(
        "!" to 0L, "," to 1L, "-" to 2L, "." to 3L, "?" to 4L,
        "AA0" to 6L, "AA1" to 7L, "AA2" to 8L,
        "AE0" to 9L, "AE1" to 10L, "AE2" to 11L,
        "AH0" to 12L, "AH1" to 13L, "AH2" to 14L,
        "AO0" to 15L, "AO1" to 16L, "AO2" to 17L,
        "AW0" to 18L, "AW1" to 19L, "AW2" to 20L,
        "AY0" to 21L, "AY1" to 22L, "AY2" to 23L,
        "B" to 24L, "CH" to 25L, "D" to 26L, "DH" to 27L,
        "EH0" to 34L, "EH1" to 35L, "EH2" to 36L,
        "ER" to 37L, "ER0" to 38L, "ER1" to 39L, "ER2" to 40L,
        "EY0" to 41L, "EY1" to 42L, "EY2" to 43L,
        "F" to 49L, "G" to 50L, "HH" to 51L,
        "I" to 52L, "IH" to 53L, "IH0" to 54L, "IH1" to 55L, "IH2" to 56L,
        "IY0" to 57L, "IY1" to 58L, "IY2" to 59L,
        "JH" to 60L, "K" to 61L, "L" to 62L, "M" to 63L, "N" to 64L,
        "NG" to 65L, "OW0" to 67L, "OW1" to 68L, "OW2" to 69L,
        "OY0" to 70L, "OY1" to 71L, "OY2" to 72L,
        "P" to 73L, "R" to 74L, "S" to 75L, "SH" to 76L,
        "SP" to 77L, "SP2" to 78L, "SP3" to 79L,
        "T" to 80L, "TH" to 81L, "U" to 82L,
        "UH0" to 83L, "UH1" to 84L, "UH2" to 85L, "UNK" to 86L,
        "UW0" to 87L, "UW1" to 88L, "UW2" to 89L,
        "V" to 90L, "W" to 91L, "Y" to 92L, "Z" to 93L, "ZH" to 94L,
        "a" to 96L, "b" to 122L, "by" to 123L, "ch" to 125L, "cl" to 126L,
        "d" to 127L, "dy" to 128L, "e" to 129L, "f" to 155L, "g" to 156L,
        "gy" to 157L, "h" to 158L, "hy" to 159L, "i" to 160L, "j" to 221L,
        "k" to 222L, "ky" to 223L, "m" to 225L, "my" to 226L, "n" to 227L,
        "ny" to 228L, "o" to 229L, "p" to 245L, "py" to 246L, "r" to 248L,
        "ry" to 249L, "s" to 250L, "sh" to 251L, "t" to 252L, "ts" to 253L,
        "u" to 254L, "v" to 295L, "w" to 316L, "y" to 318L, "z" to 319L,
        "…" to 321L, "[" to 322L, "]" to 323L,
    )

    fun ids(phones: Iterable<String>): LongArray = phones.map { phone ->
        id[phone] ?: error("Genie V2 不支持音素：$phone")
    }.toLongArray()
}
