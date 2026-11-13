package tv.cypher.core

/**
 * Channel numbers in language blocks. A number is assigned once and never changes or gets reused.
 */
object Numbering {
    const val OTHER_START = 2000
    const val OVERFLOW_START = 3000

    private data class Block(val start: Int, val width: Int)

    private val blocks: Map<String, Block> = linkedMapOf(
        "Hindi" to Block(100, 200),
        "English" to Block(300, 100),
        "Telugu" to Block(400, 100),
        "Tamil" to Block(500, 100),
        "Kannada" to Block(600, 100),
        "Malayalam" to Block(700, 100),
        "Marathi" to Block(800, 100),
        "Bengali" to Block(900, 100),
        "Gujarati" to Block(1000, 100),
        "Punjabi" to Block(1100, 100),
        "Odia" to Block(1200, 100),
        "Bhojpuri" to Block(1300, 100),
        "Assamese" to Block(1400, 100),
        "Urdu" to Block(1500, 100),
    )

    val languages: List<String> get() = blocks.keys.toList()

    /** Returns [existing] plus numbers for every id in [fresh] (id to language) that has none. */
    fun allocate(existing: Map<String, Int>, fresh: List<Pair<String, String>>): Map<String, Int> {
        val result = LinkedHashMap(existing)
        val used = existing.values.toHashSet()
        for ((id, lang) in fresh) {
            if (id in result) continue
            val block = blocks[lang] ?: Block(OTHER_START, OVERFLOW_START - OTHER_START)
            val n = (block.start until block.start + block.width).firstOrNull { it !in used }
                ?: generateSequence(OVERFLOW_START) { it + 1 }.first { it !in used }
            result[id] = n
            used.add(n)
        }
        return result
    }
}
