package com.hy.assistant.core

import java.text.Normalizer

/** Fuzzy-matches a spoken/typed name against known chat names. No LLM involved. */
object ContactMatcher {
    data class Match(val name: String, val score: Double)

    sealed interface Result {
        data class Unique(val name: String) : Result
        data class Ambiguous(val candidates: List<String>) : Result
        data object NotFound : Result
    }

    private const val MIN_SCORE = 0.6
    private const val CLEAR_WIN = 0.15

    fun rank(query: String, names: Collection<String>): List<Match> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return names.map { Match(it, score(q, normalize(it))) }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
    }

    fun resolve(query: String, names: Collection<String>): Result {
        val ranked = rank(query, names)
        if (ranked.isEmpty()) return Result.NotFound
        val top = ranked[0]
        val runnerUp = ranked.getOrNull(1)
        return if (runnerUp == null || top.score - runnerUp.score >= CLEAR_WIN) {
            Result.Unique(top.name)
        } else {
            Result.Ambiguous(ranked.takeWhile { top.score - it.score < CLEAR_WIN }.map { it.name }.take(5))
        }
    }

    internal fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun score(q: String, name: String): Double {
        if (name.isEmpty()) return 0.0
        if (q == name) return 1.0
        val tokens = name.split(' ')
        if (tokens.any { it == q }) return 0.95
        if (name.startsWith(q)) return 0.9
        if (tokens.any { it.startsWith(q) && q.length >= 2 }) return 0.85
        if (q.length >= 3 && name.contains(q)) return 0.75
        // Typos: best token-level similarity.
        val best = (tokens + name).maxOf { similarity(q, it) }
        return best * 0.85
    }

    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
