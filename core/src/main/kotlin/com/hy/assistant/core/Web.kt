package com.hy.assistant.core

import java.net.URLDecoder
import java.net.URLEncoder

data class SearchResult(val title: String, val url: String, val snippet: String)

/** Parsing helpers for web search and pages. No networking here (that lives in the app). */
object Web {
    private val ddgResult = Regex(
        """<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>(.*?)(?=<a[^>]+class="[^"]*result__a|$)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ddgSnippet = Regex(
        """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|td)>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun bingSearchUrl(query: String) = "https://www.bing.com/search?setlang=en&q=" + enc(query)

    private val bingItem = Regex("""<li class="b_algo"(.*?)</li>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val bingTitle = Regex("""<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>|<a[^>]+href="([^"]+)"[^>]*>\s*<h2[^>]*>(.*?)</h2>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val bingSnippet = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** Parses Bing's results page (organic "b_algo" items only). */
    fun parseBing(html: String, max: Int = 6): List<SearchResult> =
        bingItem.findAll(html).mapNotNull { m ->
            val body = m.groupValues[1]
            val t = bingTitle.find(body) ?: return@mapNotNull null
            val href = t.groupValues[1].ifEmpty { t.groupValues[3] }
            val title = t.groupValues[2].ifEmpty { t.groupValues[4] }
            val snippet = bingSnippet.find(body)?.groupValues?.get(1).orEmpty()
            SearchResult(clean(title), resolveBingUrl(decodeEntities(href)), clean(snippet))
        }.filter { it.title.isNotBlank() && it.url.startsWith("http") }.distinctBy { it.url }.take(max).toList()

    /** Bing sometimes links via bing.com/ck/a?...&u=a1<base64url target>. */
    fun resolveBingUrl(href: String): String {
        if (!href.contains("bing.com/ck/a")) return href
        val u = Regex("""[?&]u=a1([^&]+)""").find(href)?.groupValues?.get(1) ?: return href
        return runCatching { String(java.util.Base64.getUrlDecoder().decode(u.padEnd((u.length + 3) / 4 * 4, '=')), Charsets.UTF_8) }
            .getOrDefault(href)
    }

    fun ddgSearchUrl(query: String) = "https://html.duckduckgo.com/html/?q=" + enc(query)

    fun wikipediaSearchUrl(query: String) =
        "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=" + enc(query)

    /** Parses DuckDuckGo's HTML results page. Ads are skipped. */
    fun parseDuckDuckGo(html: String, max: Int = 6): List<SearchResult> =
        ddgResult.findAll(html).mapNotNull { m ->
            val url = resolveDdgUrl(decodeEntities(m.groupValues[1]))
            if (url.isBlank() || url.contains("duckduckgo.com/y.js")) return@mapNotNull null
            val snippet = ddgSnippet.find(m.groupValues[3])?.groupValues?.get(1).orEmpty()
            SearchResult(clean(m.groupValues[2]), url, clean(snippet))
        }.filter { it.title.isNotBlank() }.distinctBy { it.url }.take(max).toList()

    /** DDG wraps links as //duckduckgo.com/l/?uddg=<encoded target>. */
    fun resolveDdgUrl(href: String): String {
        val u = if (href.startsWith("//")) "https:$href" else href
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(u)?.groupValues?.get(1)
        return if (uddg != null) URLDecoder.decode(uddg, "UTF-8") else u
    }

    /** Parses the Wikipedia search API JSON (title + snippet) without a JSON library. */
    fun parseWikipedia(json: String, max: Int = 5): List<SearchResult> =
        Regex(""""title":"((?:[^"\\]|\\.)*)".*?"snippet":"((?:[^"\\]|\\.)*)"""")
            .findAll(json).map { m ->
                val title = unescapeJson(m.groupValues[1])
                SearchResult(title, "https://en.wikipedia.org/wiki/" + enc(title.replace(' ', '_')), clean(unescapeJson(m.groupValues[2])))
            }.take(max).toList()

    /** Readable text of an HTML page: drops scripts/styles/nav, keeps paragraphs as lines. */
    fun htmlToText(html: String, maxChars: Int = 4000): String {
        var s = html
        s = Regex("""<(script|style|noscript|svg|nav|footer|header|form)\b.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(s, " ")
        s = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL).replace(s, " ")
        s = Regex("""<(br|/p|/div|/li|/h[1-6]|/tr)\b[^>]*>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("""<[^>]+>""").replace(s, " ")
        s = decodeEntities(s)
        val lines = s.lines().map { it.replace(Regex("""[ \t ]+"""), " ").trim() }.filter { it.length > 1 }
        return lines.joinToString("\n").take(maxChars)
    }

    /** Formats results (+ optional page text of the top hit) for the answer prompt. */
    fun formatResults(results: List<SearchResult>, topPageText: String?): String {
        val sb = StringBuilder()
        results.forEachIndexed { i, r ->
            sb.append(i + 1).append(". ").append(r.title).append(" (").append(host(r.url)).append(")\n")
            if (r.snippet.isNotBlank()) sb.append("   ").append(r.snippet).append("\n")
        }
        if (!topPageText.isNullOrBlank()) sb.append("\nFrom ").append(host(results.first().url)).append(":\n").append(topPageText)
        return sb.toString()
    }

    /** Turns "youtube lofi music", "github.com" or a URL into a URL to open. */
    fun browseUrl(target: String): String {
        val t = target.trim()
        if (Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        if (Regex("""^[\w-]+(\.[\w-]+)+(/\S*)?$""").matches(t)) return "https://$t"
        val words = t.split(Regex("\\s+"), limit = 2)
        val site = words[0].lowercase()
        val rest = words.getOrNull(1).orEmpty()
        val siteSearch = mapOf(
            "youtube" to "https://m.youtube.com/results?search_query=",
            "google" to "https://www.google.com/search?q=",
            "wikipedia" to "https://en.m.wikipedia.org/w/index.php?search=",
            "amazon" to "https://www.amazon.in/s?k=",
            "flipkart" to "https://www.flipkart.com/search?q=",
            "github" to "https://github.com/search?q=",
            "maps" to "https://www.google.com/maps/search/",
            "reddit" to "https://www.reddit.com/search/?q=",
        )
        siteSearch[site]?.let { base -> return if (rest.isBlank()) base.substringBefore("/search").substringBefore("/results").substringBefore("/s?").substringBefore("/w/") else base + enc(rest) }
        return "https://duckduckgo.com/?q=" + enc(t)
    }

    fun host(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.")

    private fun clean(html: String) = decodeEntities(Regex("<[^>]+>").replace(html, "")).replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(s: String): String = s
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&")

    private fun unescapeJson(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})|\\(.)""").replace(s) { m ->
            if (m.groupValues[1].isNotEmpty()) m.groupValues[1].toInt(16).toChar().toString()
            else when (val c = m.groupValues[2]) { "n" -> "\n"; "t" -> "\t"; else -> c }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** Flags shell commands that can destroy data or change the system, so the UI can warn before Run. */
object CommandSafety {
    private val rules = listOf(
        Regex("""(^|[;&|]\s*)(sudo\s+)?rm\s""") to "deletes files",
        Regex("""\b(dd|mkfs\S*|shred|wipe)\b""") to "can overwrite disks or data",
        Regex("""\bchmod\s+(-R|777)|\bchown\s""") to "changes file permissions",
        Regex("""(curl|wget)[^|]*\|\s*(ba|z)?sh""") to "runs a script downloaded from the internet",
        Regex("""(^|[^>2&])>\s*[/~\w]""") to "overwrites a file",
        Regex("""\b(su|reboot|setprop|pm\s+(uninstall|clear)|am\s+force-stop|kill(all)?)\b""") to "changes the system or apps",
        Regex("""\b(mv)\s""") to "moves/renames files",
        Regex(""":\(\)\s*\{""") to "fork bomb",
    )

    /** Human-readable warnings; empty means read-only-looking. */
    fun warnings(command: String): List<String> = rules.filter { (r, _) -> r.containsMatchIn(command) }.map { it.second }.distinct()
}
