package com.hy.assistant.core

/**
 * The GPU speed test: the same fixed prompt runs greedily on the CPU and on the GPU, and the GPU
 * is only recommended when it is clearly faster AND gives the same answer (a GPU driver that
 * computes wrong numbers would otherwise write nonsense into people's chats).
 */
object GpuBench {
    data class Run(val promptTokens: Int, val promptMs: Double, val genTokens: Int, val genMs: Double, val text: String) {
        val promptPerSec: Double get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
        val genPerSec: Double get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0

        /** Seconds for a typical job: reading a ~600-token chat and writing a ~60-token reply. */
        fun typicalJobSec(): Double {
            if (promptPerSec <= 0 || genPerSec <= 0) return Double.MAX_VALUE
            return TYPICAL_PROMPT / promptPerSec + TYPICAL_REPLY / genPerSec
        }
    }

    enum class Verdict { FASTER, NOT_FASTER, WRONG_OUTPUT }

    private const val TYPICAL_PROMPT = 600.0
    private const val TYPICAL_REPLY = 60.0

    fun verdict(cpu: Run, gpu: Run): Verdict = when {
        !sameAnswer(cpu.text, gpu.text) -> Verdict.WRONG_OUTPUT
        gpu.typicalJobSec() < cpu.typicalJobSec() * 0.9 -> Verdict.FASTER
        else -> Verdict.NOT_FASTER
    }

    /**
     * GPU math rounds slightly differently, so a greedy answer can drift after a while; a working
     * GPU still starts the same way and uses mostly the same words. Garbage shares neither.
     */
    fun sameAnswer(a: String, b: String): Boolean {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        if (wa.take(5) == wb.take(5)) return true
        val sa = wa.toSet()
        val sb = wb.toSet()
        return sa.intersect(sb).size.toDouble() / sa.union(sb).size >= 0.6
    }

    private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}']+""")).filter { it.isNotBlank() }

    /** A realistic chat-sized prompt (a few hundred tokens) with a short, predictable answer. */
    fun prompt(): Prompt {
        val chat = buildString {
            val lines = listOf(
                "Priya: Hey! Are we still on for the trip planning call this weekend?",
                "Me: Yes, Saturday works for me. What time suits you?",
                "Priya: How about 11 in the morning? I'll share the hotel options before that.",
                "Me: 11 is perfect. Did Rahul confirm if he can take leave on Friday?",
                "Priya: He said probably, but he needs to check with his manager first.",
                "Me: Okay. We should book the train tickets soon, prices are going up.",
                "Priya: Agreed. I saw the morning train leaves at 6:40 and reaches by 1 pm.",
                "Me: That's early but fine. Can you also check if the hotel has free breakfast?",
                "Priya: Sure, I'll ask them. The second hotel is closer to the beach though.",
                "Me: Closer to the beach sounds better. Let's compare prices on Saturday.",
            )
            repeat(3) { lines.forEach { appendLine(it) } }
        }
        return Prompt(
            system = "You summarize chats in English. Answer in one short sentence.",
            user = "<messages>\n$chat</messages>\nWhat time is the call on Saturday, and what will Priya share before it?",
            maxTokens = 40,
            temperature = 0f,
        )
    }
}
