package com.hy.assistant.core

import java.util.Calendar

/** A UI element read through Accessibility (a simplified copy, so parsing can be unit-tested). */
data class ScreenNode(
    val viewId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val className: String? = null,
    val isPassword: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val children: List<ScreenNode> = emptyList(),
) {
    val centerX: Int get() = (left + right) / 2
}

object ScreenText {
    /** Every element in reading order (top-to-bottom, then left-to-right). Password fields are never included. */
    fun flatten(root: ScreenNode): List<ScreenNode> {
        val out = mutableListOf<ScreenNode>()
        fun walk(n: ScreenNode) {
            if (n.isPassword) return
            out += n
            n.children.forEach(::walk)
        }
        walk(root)
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /**
     * Readable text of a screen: visible labels in order, consecutive duplicates removed, and
     * bare UI chrome (single icons, "Back", "More options") dropped. Capped for the prompt.
     */
    fun toText(root: ScreenNode, maxChars: Int = 6000): String {
        val lines = mutableListOf<String>()
        for (n in flatten(root)) {
            val t = (n.text?.takeIf { it.isNotBlank() } ?: n.desc)?.trim()?.replace(Regex("\\s+"), " ") ?: continue
            if (t.length < 2 || t.lowercase() in CHROME) continue
            if (lines.lastOrNull() == t) continue
            lines += t
        }
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.length + l.length + 1 > maxChars) break
            sb.append(l).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private val CHROME = setOf(
        "back", "navigate up", "more options", "search", "menu", "close", "attach", "camera", "emoji", "voice message",
        "send", "video call", "voice call", "call", "home", "overview", "share", "settings",
    )
}

/** Reads a WhatsApp chat screen: contact name + visible messages (yours are on the right). */
object WhatsAppScreen {
    data class Message(val text: String, val fromMe: Boolean, val time: String?)
    data class Chat(val name: String, val messages: List<Message>)

    private val timeRx = Regex("""^\d{1,2}:\d{2}(\s?[ap]\.?m\.?)?$""", RegexOption.IGNORE_CASE)

    fun parse(root: ScreenNode, screenWidth: Int): Chat? {
        if (screenWidth <= 0) return null
        val nodes = ScreenText.flatten(root)
        val name = nodes.firstOrNull { it.viewId?.endsWith("conversation_contact_name") == true }?.text?.trim()
            ?: return null // not a chat screen
        val messages = mutableListOf<Message>()
        for ((i, n) in nodes.withIndex()) {
            if (n.viewId?.endsWith("message_text") != true) continue
            val text = n.text?.trim().orEmpty()
            if (text.isEmpty()) continue
            // Outgoing bubbles sit on the right; incoming ones start at the left edge.
            val fromMe = n.left > screenWidth / 4
            // The time label is the next short "8:53 pm"-style text close below/after the message.
            val time = nodes.drop(i + 1).take(4).firstOrNull { it.text?.trim()?.let(timeRx::matches) == true }?.text?.trim()
            messages += Message(text, fromMe, time)
        }
        return if (messages.isEmpty()) null else Chat(name, messages)
    }

    /** "8:53 pm" today → epoch millis (null if it can't be read). */
    fun timeToday(label: String?, now: Calendar = Calendar.getInstance()): Long? {
        val m = Regex("""^(\d{1,2}):(\d{2})\s?([ap])?""", RegexOption.IGNORE_CASE).find(label?.trim() ?: return null) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        when (m.groupValues[3].lowercase()) {
            "p" -> if (h < 12) h += 12
            "a" -> if (h == 12) h = 0
        }
        if (h > 23 || min > 59) return null
        val c = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }
}
