package tv.cypher.core

object Names {
    private val brackets = Regex("""\([^()]*\)|\[[^\]]*]""")
    private val trailingQuality = Regex("""\s+(hd|sd|uhd|fhd|4k)$""")
    private val earlierPrefix = Regex(
        """^\s*(earlier|earlie|earleir|erlier|formerly|previously|erstwhile|before that)\b(\s+known\s+as|\s+known)?\s*[:\-]?\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Lower-case comparison key: no brackets, no punctuation, no trailing HD/SD. */
    fun normalize(name: String): String {
        var s = name
        while (true) {
            val t = brackets.replace(s, " ")
            if (t == s) break
            s = t
        }
        s = s.lowercase()
            .replace("&", " and ")
            .replace("+", " plus ")
        s = buildString {
            for (c in s) append(if (c.isLetterOrDigit()) c else ' ')
        }
        s = s.trim().replace(Regex("\\s+"), " ")
        while (true) {
            val t = trailingQuality.replace(s, "")
            if (t == s) break
            s = t
        }
        return s
    }

    /** The name with every parenthetical removed. */
    fun primary(full: String): String {
        var s = full
        while (true) {
            val t = brackets.replace(s, " ")
            if (t == s) break
            s = t
        }
        // Unbalanced leftovers such as "(earlier X" -> cut at the first "(".
        s = s.substringBefore('(')
        return s.trim().replace(Regex("\\s+"), " ")
    }

    /** Alternative / earlier names found in parentheses. */
    fun aliases(full: String): List<String> {
        if ('(' !in full) return emptyList()
        // Flatten nesting and unbalanced brackets: every "(" starts a clause, ")" ends one.
        val clauses = full.substringAfter('(').split('(').map { it.replace(")", " ") }
        val out = LinkedHashSet<String>()
        for (clause in clauses) {
            var text = clause.trim()
            text = earlierPrefix.replace(text, "")
            for (part in text.split(',', '/')) {
                val p = part.trim().replace(Regex("\\s+"), " ")
                if (p.isNotEmpty()) out.add(p)
            }
        }
        return out.toList()
    }
}
