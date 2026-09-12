package dev.voicecommander.core

/**
 * RAW transcript: finished recognition results plus one live partial.
 * SpeechRecognizer partials replace the previous partial wholesale, so the
 * model is finals + one mutable tail — never blind string concatenation.
 */
class TranscriptBuffer {
    private val finals = mutableListOf<String>()
    private var partial: String? = null

    val text: String
        get() {
            var out = join(finals, "")
            val p = partial
            if (!p.isNullOrEmpty()) {
                out = join(listOf(out, p)).trim()
            }
            return out.trim()
        }

    fun onPartial(text: String) {
        if (text.isNotBlank()) partial = text
    }

    fun onFinal(text: String) {
        if (text.isNotBlank()) {
            // A final supersedes whatever partial accumulated for the same segment.
            if (partial != null && (partial!! == text || text.startsWith(partial!!))) partial = null
            finals.add(text)
            partial = null
        }
    }

    fun reset() {
        finals.clear()
        partial = null
    }

    companion object {
        /** Japanese reads wrong with spaces between segments; Latin wrong without. */
        fun join(parts: List<String>, _unused: String = ""): String =
            parts.filter { it.isNotBlank() }.reduceOrNull { acc, next ->
                acc + (joinsWithoutSpace(acc, next) ?: " ") + next
            } ?: ""

        private fun joinsWithoutSpace(left: String, right: String): String? {
            val l = left.lastOrNull() ?: return null
            val r = right.firstOrNull() ?: return null
            return if (isCJK(l) || isCJK(r)) "" else null
        }

        private fun isCJK(c: Char): Boolean {
            val v = c.code
            return v in 0x3000..0x30FF || v in 0x3400..0x4DBF || v in 0x4E00..0x9FFF ||
                v in 0xF900..0xFAFF || v in 0xFF00..0xFF60
        }
    }
}