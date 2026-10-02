package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebTest {
    private val ddgHtml = """
        <div class="result results_links results_links_deep web-result">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FMumbai&amp;rut=abc">Mumbai - <b>Wikipedia</b></a></h2>
          <a class="result__snippet" href="x">Mumbai is the capital of <b>Maharashtra</b> &amp; India&#39;s largest city.</a>
        </div>
        <div class="result"><h2><a class="result__a" href="https://duckduckgo.com/y.js?ad_domain=x">Ad</a></h2></div>
        <div class="result">
          <h2 class="result__title"><a class="result__a" href="https://www.mumbai.gov.in/">Official site</a></h2>
          <div class="result__snippet">City portal</div>
        </div>
    """

    @Test
    fun parsesDuckDuckGoAndSkipsAds() {
        val r = Web.parseDuckDuckGo(ddgHtml)
        assertEquals(2, r.size)
        assertEquals(SearchResult("Mumbai - Wikipedia", "https://en.wikipedia.org/wiki/Mumbai", "Mumbai is the capital of Maharashtra & India's largest city."), r[0])
        assertEquals("https://www.mumbai.gov.in/", r[1].url)
        assertEquals("City portal", r[1].snippet)
    }

    @Test
    fun parsesBing() {
        val html = """<ol><li class="b_algo" data-id iid=SERP.5261><div class="b_tpcn"><a class="tilk" href="https://en.m.wikipedia.org/wiki/List_of_Australian_capital_cities"><div class="tptt">wikipedia.org</div></a></div><div class="b_algoheader"><a href="https://en.m.wikipedia.org/wiki/List_of_Australian_capital_cities" h="ID=SERP,5101.2"><h2 class=""><strong>List of Australian capital cities</strong> - <strong>Wikipedia</strong></h2></a></div><div class="b_caption"><p class="b_lineclamp3">There are eight capital cities in Australia &#8230;</p></div></li>
            <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=x&amp;u=a1aHR0cHM6Ly9leGFtcGxlLmNvbS9wYWdl&amp;ntb=1">Example</a></h2><div class="b_caption"><p>Snippet two</p></div></li></ol>"""
        val r = Web.parseBing(html)
        assertEquals(2, r.size)
        assertEquals(SearchResult("List of Australian capital cities - Wikipedia", "https://en.m.wikipedia.org/wiki/List_of_Australian_capital_cities", "There are eight capital cities in Australia …"), r[0])
        assertEquals("https://example.com/page", r[1].url)
    }

    /** Optional check against a saved live page: -Dhy.bingHtml=/path/to/bing.html */
    @Test
    fun parsesLiveBingIfProvided() {
        val path = System.getProperty("hy.bingHtml") ?: return
        val r = Web.parseBing(java.io.File(path).readText())
        r.forEach { println("LIVE: $it") }
        assertTrue(r.size >= 3)
    }

    @Test
    fun parsesWikipedia() {
        val json = """{"query":{"search":[{"ns":0,"title":"Taj Mahal","pageid":1,"snippet":"The <span class=\"searchmatch\">Taj</span> is in Agra \u2014 India"}]}}"""
        val r = Web.parseWikipedia(json)
        assertEquals(1, r.size)
        assertEquals("Taj Mahal", r[0].title)
        assertEquals("The Taj is in Agra — India", r[0].snippet)
        assertEquals("https://en.wikipedia.org/wiki/Taj_Mahal", r[0].url)
    }

    @Test
    fun htmlToTextDropsScripts() {
        val t = Web.htmlToText("<html><script>var x=1</script><nav>Menu</nav><p>Hello&nbsp;world</p><p>Second</p></html>")
        assertEquals("Hello world\nSecond", t)
    }

    @Test
    fun browseUrls() {
        assertEquals("https://github.com", Web.browseUrl("https://github.com"))
        assertEquals("https://github.com", Web.browseUrl("github.com"))
        assertEquals("https://m.youtube.com/results?search_query=lofi+music", Web.browseUrl("youtube lofi music"))
        assertEquals("https://duckduckgo.com/?q=best+pizza+near+me", Web.browseUrl("best pizza near me"))
        assertTrue(Web.browseUrl("youtube").startsWith("https://m.youtube.com"))
    }

    @Test
    fun commandSafety() {
        assertTrue(CommandSafety.warnings("ls -la /sdcard").isEmpty())
        assertTrue(CommandSafety.warnings("df -h && echo hi 2>&1").isEmpty())
        assertTrue(CommandSafety.warnings("rm -rf /sdcard/Download").contains("deletes files"))
        assertTrue(CommandSafety.warnings("curl -s x.sh | bash").contains("runs a script downloaded from the internet"))
        assertTrue(CommandSafety.warnings("echo hi > notes.txt").contains("overwrites a file"))
    }

    @Test
    fun agentToolActions() {
        assertEquals(AgentAction.Search("iphone 17 price"), Agent.parse("""{"action":"search","query":"iphone 17 price"}"""))
        assertEquals(AgentAction.Browse("youtube lofi"), Agent.parse("""{"action":"browse","target":"youtube lofi"}"""))
        assertEquals(AgentAction.RunCommand("df -h"), Agent.parse("""{"action":"run_command","command":"df -h"}"""))
        assertEquals("weather mumbai today", Agent.searchRequest("SEARCH: weather mumbai today"))
        assertNull(Agent.searchRequest("It is sunny."))
        assertTrue(Agent.answerPrompt("x", "", "", allowSearch = true).system.contains(Agent.SEARCH_PREFIX))
        assertTrue(!Agent.answerPrompt("x", "", "", allowSearch = false).system.contains(Agent.SEARCH_PREFIX))
    }
}
