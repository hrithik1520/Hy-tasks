package com.hy.assistant.core

/** A phone action Hy can do directly (open apps, chats, SMS, calls, alarms…). */
data class PhoneAction(val kind: Kind, val target: String = "", val text: String = "") {
    enum class Kind(val id: String) {
        OPEN_APP("open_app"),
        WHATSAPP("whatsapp"), // open a chat, read it, draft (or use [text]), type it, wait for the user's Send
        SMS("sms"),
        CALL("call"),
        ALARM("alarm"),
        TIMER("timer"),
        NAVIGATE("navigate"),
        YOUTUBE("youtube"),
        ;

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id }
        }
    }
}

/** Rules for common phone-action phrasings (instant, no AI). Anything else goes to the router. */
object PhoneActions {
    private val rules: List<Pair<Regex, (MatchResult) -> PhoneAction>> = listOf(
        // WhatsApp: "message rahul on whatsapp saying i'm late", "whatsapp mom", "reply to priya on whatsapp"
        Regex("""^(?:send (?:a )?)?(?:message|msg|text|reply to|write to|whatsapp) (.+?) on whatsapp(?:,|:| saying| that)\s+(.+)$""")
            to { m -> PhoneAction(PhoneAction.Kind.WHATSAPP, m.groupValues[1], m.groupValues[2]) },
        Regex("""^(?:open )?whatsapp (?:chat (?:with|of) )?(.+?)(?:,|:| saying| that)\s+(.+)$""")
            to { m -> PhoneAction(PhoneAction.Kind.WHATSAPP, m.groupValues[1], m.groupValues[2]) },
        Regex("""^(?:reply to|message|text|msg|write to|open) (.+?)(?:'s)? (?:on|in) whatsapp$""")
            to { m -> PhoneAction(PhoneAction.Kind.WHATSAPP, m.groupValues[1]) },
        Regex("""^(?:open )?whatsapp (?:chat (?:with|of) )?(.+)$""")
            to { m -> PhoneAction(PhoneAction.Kind.WHATSAPP, m.groupValues[1]) },
        Regex("""^open (?:my )?(?:chat|conversation) with (.+)$""")
            to { m -> PhoneAction(PhoneAction.Kind.WHATSAPP, m.groupValues[1]) },
        // SMS
        Regex("""^(?:send (?:an? )?)?(?:sms|text message) (?:to )?(.+?)(?:,|:| saying| that)\s+(.+)$""")
            to { m -> PhoneAction(PhoneAction.Kind.SMS, m.groupValues[1], m.groupValues[2]) },
        // Calls
        Regex("""^(?:call|phone|ring|dial) (.+)$""") to { m -> PhoneAction(PhoneAction.Kind.CALL, m.groupValues[1]) },
        // Alarms and timers
        Regex("""^(?:set (?:an? )?)?alarm (?:for |at )?(.+)$""") to { m -> PhoneAction(PhoneAction.Kind.ALARM, m.groupValues[1]) },
        Regex("""^wake me (?:up )?(?:at )?(.+)$""") to { m -> PhoneAction(PhoneAction.Kind.ALARM, m.groupValues[1]) },
        Regex("""^(?:set (?:an? )?)?timer (?:for )?(.+)$""") to { m -> PhoneAction(PhoneAction.Kind.TIMER, m.groupValues[1]) },
        Regex("""^(.+?) timer$""") to { m -> PhoneAction(PhoneAction.Kind.TIMER, m.groupValues[1]) },
        // Maps / YouTube
        Regex("""^(?:navigate|directions|take me|drive|route) to (.+)$""") to { m -> PhoneAction(PhoneAction.Kind.NAVIGATE, m.groupValues[1]) },
        Regex("""^play (.+?) on youtube$""") to { m -> PhoneAction(PhoneAction.Kind.YOUTUBE, m.groupValues[1]) },
        // Apps: "open spotify", "launch camera" (after the WhatsApp/chat rules above)
        Regex("""^(?:open|launch|start|fire up|boot up|load|go to) (?:the )?(?:app )?(.+?)(?: app)?(?: for me| please)?$""") to { m -> PhoneAction(PhoneAction.Kind.OPEN_APP, m.groupValues[1]) },
        Regex("""^(?:can you |could you |please )?(?:get|bring|pull|put) (?:the )?(.+?)(?: app)? (?:up|open)(?: for me| please)?\??$""") to { m -> PhoneAction(PhoneAction.Kind.OPEN_APP, m.groupValues[1]) },
    )

    /** null when the text isn't a phone action (or a timer/alarm whose time can't be read). */
    fun parse(input: String): PhoneAction? {
        val original = input.trim().trimEnd('.', '!')
        val lower = original.lowercase()
        for ((rx, make) in rules) {
            val m = rx.matchEntire(lower) ?: continue
            var a = make(m)
            // Keep the user's casing for message text.
            if (a.text.isNotEmpty()) a = a.copy(text = original.substring(original.length - a.text.length).trim())
            a = a.copy(target = a.target.trim().removePrefix("the ").removePrefix("my ").trim())
            if (a.target.isEmpty()) continue
            if (a.kind == PhoneAction.Kind.ALARM && parseClock(a.target) == null) continue
            if (a.kind == PhoneAction.Kind.TIMER && parseDurationSeconds(a.target) == null) continue
            return a
        }
        return null
    }

    /** "6:30am", "7 pm", "19:45", "6.30 am" → (hour, minute). */
    fun parseClock(s: String): Pair<Int, Int>? {
        spokenClock(s.trim().lowercase())?.let { return it }
        val m = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?(?: tomorrow| today)?$""").find(s.trim().lowercase()) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3]
        if (ap.isEmpty() && m.groupValues[2].isEmpty() && h > 12) return null // "alarm 45" is not a time
        if (ap.startsWith("p") && h < 12) h += 12
        if (ap.startsWith("a") && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    private val numberWords = listOf(
        "twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
    )

    /** "quarter past six", "half past 7 pm", "quarter to eight", "six thirty", "seven o'clock". */
    private fun spokenClock(t: String): Pair<Int, Int>? {
        val hourRx = """(\d{1,2}|${numberWords.joinToString("|")})"""
        fun hourOf(w: String): Int? = w.toIntOrNull() ?: numberWords.indexOf(w).takeIf { it >= 0 }?.let { if (it == 0) 12 else it }
        fun withAmPm(h: Int, rest: String): Int = when {
            rest.contains("pm") || rest.contains("evening") || rest.contains("night") -> if (h < 12) h + 12 else h
            rest.contains("am") || rest.contains("morning") -> if (h == 12) 0 else h
            else -> h
        }
        Regex("""^(quarter|half) (past|to) $hourRx(.*)$""").find(t)?.let { m ->
            var h = hourOf(m.groupValues[3]) ?: return null
            val min = if (m.groupValues[1] == "half") 30 else if (m.groupValues[2] == "past") 15 else 45
            if (m.groupValues[2] == "to") h = if (h == 1) 12 else h - 1
            return withAmPm(h, m.groupValues[4]) % 24 to min
        }
        Regex("""^$hourRx (o'?clock|thirty|fifteen|forty[- ]five)(.*)$""").find(t)?.let { m ->
            val h = hourOf(m.groupValues[1]) ?: return null
            val min = when (m.groupValues[2]) { "thirty" -> 30; "fifteen" -> 15; "o'clock", "oclock" -> 0; else -> 45 }
            return withAmPm(h, m.groupValues[3]) % 24 to min
        }
        return null
    }

    /** "10 minutes", "1 hour 30 min", "90 sec", "5 min" → seconds. */
    fun parseDurationSeconds(s: String): Int? {
        var total = 0
        var found = false
        for (m in Regex("""(\d+)\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\b""").findAll(s.lowercase())) {
            val n = m.groupValues[1].toInt()
            total += when (m.groupValues[2].first()) {
                'h' -> n * 3600
                'm' -> n * 60
                else -> n
            }
            found = true
        }
        return if (found && total in 1..86_400) total else null
    }

    /**
     * Phone number → digits for a wa.me link. Local numbers ("98765 43210", "098765…") get the
     * default country code; "+44…"/"0044…" keep theirs.
     */
    fun whatsAppNumber(raw: String, defaultCountryCode: String): String? {
        val t = raw.trim()
        var d = t.filter { it.isDigit() }
        if (d.isEmpty()) return null
        when {
            t.startsWith("+") -> Unit
            d.startsWith("00") -> d = d.drop(2)
            d.length == 10 -> d = defaultCountryCode + d
            d.length == 11 && d.startsWith("0") -> d = defaultCountryCode + d.drop(1)
        }
        return d.takeIf { it.length in 8..15 }
    }

    /** Country calling code for a SIM/network ISO country (small table; the rest fall back to India). */
    fun countryCode(iso: String?): String = when (iso?.lowercase()) {
        "in" -> "91"; "us", "ca" -> "1"; "gb", "uk" -> "44"; "ae" -> "971"; "sa" -> "966"; "au" -> "61"
        "sg" -> "65"; "de" -> "49"; "fr" -> "33"; "it" -> "39"; "es" -> "34"; "nl" -> "31"; "ie" -> "353"
        "qa" -> "974"; "kw" -> "965"; "om" -> "968"; "bh" -> "973"; "my" -> "60"; "nz" -> "64"; "za" -> "27"
        "pk" -> "92"; "bd" -> "880"; "lk" -> "94"; "np" -> "977"; "pt" -> "351"
        else -> "91"
    }
}

/** Finds WhatsApp's message box and Send button in a screen tree (by id, then by class/label). */
object WhatsAppControls {
    fun inputField(nodes: List<ScreenNode>): ScreenNode? =
        nodes.firstOrNull { it.viewId?.endsWith(":id/entry") == true }
            ?: nodes.lastOrNull { it.className?.endsWith("EditText") == true }

    fun sendButton(nodes: List<ScreenNode>): ScreenNode? =
        nodes.firstOrNull { it.viewId?.endsWith(":id/send") == true }
            ?: nodes.firstOrNull { it.desc.equals("Send", ignoreCase = true) }
}
