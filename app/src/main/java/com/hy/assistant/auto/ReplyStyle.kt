package com.hy.assistant.auto

import com.hy.assistant.SettingsData
import com.hy.assistant.core.Humanizer
import com.hy.assistant.core.TextCleanup
import com.hy.assistant.notifications.Chat

/** Applies the humanizer to every WhatsApp reply Hy writes (suggestions, auto-replies, agent sends). */
object ReplyStyle {
    /** Extra prompt rules for this chat: write like a person + match how the user texts here. */
    fun promptRules(chat: Chat, s: SettingsData): String =
        if (!s.humanizeReplies) "" else Humanizer.PROMPT_RULES + style(chat).promptHint()

    /** Final clean-up of a drafted reply (no extra AI call). */
    fun finish(text: String, chat: Chat?, s: SettingsData): String {
        val cleaned = TextCleanup.cleanReply(text)
        if (!s.humanizeReplies || cleaned.isBlank()) return cleaned
        val theyUseEmoji = chat?.messages?.any { !it.fromMe && Humanizer.hasEmoji(it.text) } ?: true
        return Humanizer.apply(cleaned, s.tone, chat?.let { style(it) } ?: Humanizer.Style.NONE, theyUseEmoji)
    }

    private fun style(chat: Chat) = Humanizer.Style.from(chat.messages.filter { it.fromMe }.map { it.text })
}
