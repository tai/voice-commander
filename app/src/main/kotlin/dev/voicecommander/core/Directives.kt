package dev.voicecommander.core

/**
 * Spoken directives are recognized before the interpretation request so they
 * become instructions about the INTENT, never content inside it.
 */
enum class Directive { NONE, SHORTER, ENGLISH, VERBATIM }

data class ParsedUtterance(val content: String, val directive: Directive)

object DirectiveParser {
    private val phrases: List<Pair<Directive, List<String>>> = listOf(
        Directive.SHORTER to listOf("もっと短く", "短くして", "簡潔に", "make it shorter", "shorter"),
        Directive.ENGLISH to listOf("英語で", "in english", "in English"),
        Directive.VERBATIM to listOf("この通り入力して", "そのまま入力して", "verbatim", "as is"),
    )

    fun parse(raw: String): ParsedUtterance {
        val trimmed = raw.trim()
        for ((dir, ps) in phrases) {
            for (p in ps) {
                val idx = trimmed.indexOf(p, ignoreCase = true)
                if (idx >= 0) {
                    val content = (trimmed.removeRange(idx, idx + p.length)).trim()
                    return ParsedUtterance(if (content.isEmpty()) trimmed else content, dir)
                }
            }
        }
        return ParsedUtterance(trimmed, Directive.NONE)
    }
}