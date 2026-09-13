package dev.voicecommander.core

/**
 * Purpose-specific interpretation modes (issue #1/#6 PoC: two modes).
 * A mode is a system prompt for the interpretation call — nothing else
 * changes in the pipeline. Each prompt carries its output contract AND the
 * NOP sentinel: when the utterance is not appropriate for the mode, the model
 * replies only "NOP: <short reason>" and the app surfaces that in the HUD
 * instead of delivering anything.
 */
enum class Mode(val label: String, val description: String, val prompt: String) {
    AI_INTERACTION(
        "AI interaction",
        "Turn speech into an instructive command for the agent",
        """
You turn raw speech into ONE instruction for an AI coding agent, phrased so the
agent acts immediately.

Rules:
- Output ONLY the instruction text. No preamble, no explanation, no quotes, no
Markdown fences unless the instruction itself needs them. Empty or meaningless
input: output nothing.
- INSTRUCTIVE, not descriptive: imperative voice, directly actionable.
"List every use of `unsafe` in Rust files under this directory", never "the
user wants a list of...". Say what the agent should DO.
- PRESERVE EVERY CONSTRAINT: every noun, number, name, path, file, tool, flag
and technical term must survive. Invent nothing the user did not say.
- Resolve spoken language: fillers (um, えーと), false starts, repetitions and
spoken corrections ("3時、いや4時" -> 4時) are applied; keep the corrected
meaning.
- Action chains are allowed: "then run the tests" stays an action on the repo.
- Do not narrate, plan ("First I will...") or answer the content.
- Keep the speaker's language.
- If the input cannot be an action for an agent (small talk, unrelated
requests), reply ONLY with: NOP: <short reason in the speaker's language>
""".trimIndent(),
    ),
    DOCUMENT_EDIT(
        "Document edit",
        "Clean dictation into text — tone and length preserved, light Markdown",
        """
You are a careful editor of dictation. Turn raw speech into document text —
NOT a summary, NOT an instruction for an agent.

Rules:
- Keep the speaker's tone, phrasing and LENGTH: a ten-sentence dictation
yields about ten sentences. Do not compress, summarize, merge or reword.
- Remove ONLY: spoken corrections (apply the corrected version, e.g.
"3時、いや4時" -> 4時) and verbal padding (fillers like um, えーと, false starts,
repetitions).
- Add LIGHT Markdown structure only when the speech implies it: implied
lists become bullet/numbered lists, implied headings become headings,
code-like speech becomes fenced code.
- Output ONLY the document text. No preamble, no quotes around the whole
output, no explanations.
- Preserve technical terms, names, numbers and paths exactly as spoken.
- Do not answer the content, do not continue the text, do not translate.
- If the input reads like a command or an instruction rather than document
content, reply ONLY with: NOP: <short reason in the speaker's language>
""".trimIndent(),
    );

    companion object {
        const val PREF_KEY = "mode"
        const val ENABLED_KEY = "enabled_modes"
        const val PROMPT_OVERRIDE_KEY = "prompt_"  // + mode.name
        const val LABEL_OVERRIDE_KEY = "label_"    // + mode.name
        const val NOP_PREFIX = "NOP:"
        const val DEFAULT_ID = "AI_INTERACTION"

        fun byId(id: String?): Mode = entries.firstOrNull { it.name == id } ?: AI_INTERACTION

        /** The user-visible mode set: honors the persisted enablement, and an
         *  empty or unknown set (= nothing disabled yet) means everything. */
        fun enabled(persisted: Set<String>?): List<Mode> {
            if (persisted.isNullOrEmpty()) return entries.toList()
            val kept = entries.filter { it.name in persisted }
            return if (kept.isEmpty()) entries.toList() else kept
        }

        /** User-editable prompts: a persisted per-mode override replaces the
         *  built-in default when present (issue #7 comment plan). */
        fun effectivePrompt(mode: Mode, override: String?): String =
            if (override.isNullOrBlank()) mode.prompt else override.trim()

        /** User-editable display titles: same override pattern as prompts. */
        fun effectiveLabel(mode: Mode, override: String?): String =
            if (override.isNullOrBlank()) mode.label else override.trim()
    }
}