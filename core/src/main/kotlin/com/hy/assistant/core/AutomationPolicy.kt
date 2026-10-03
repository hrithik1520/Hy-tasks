package com.hy.assistant.core

/** What Hy should do when a new message arrives in a chat. */
sealed interface AutoDecision {
    /** Do nothing (no draft, no notification, no battery spent). */
    data object None : AutoDecision
    /** Draft a reply and show it as a notification with Send. */
    data object Suggest : AutoDecision
    /** Draft and send it after the countdown. */
    data object Auto : AutoDecision
    /** Auto mode wanted, but a safety rule says the user must decide: suggest, labelled with why. */
    data class Hold(val reason: String) : AutoDecision
}

/** Pure rules behind Manual/Auto mode, kept here so they can be unit-tested. */
object AutomationPolicy {
    enum class ChatSetting { DEFAULT, AUTO, MANUAL, OFF }

    data class Input(
        val chatName: String,
        val isWhatsApp: Boolean,
        val isGroup: Boolean,
        /** WhatsApp's reply action is live (without it, Send can't work). */
        val canReply: Boolean,
        val incoming: List<String>,
        val chatSetting: ChatSetting,
        val globalAuto: Boolean,
        val proactiveSuggestions: Boolean,
        val includeGroups: Boolean,
        val includeOtherApps: Boolean,
        val inCooldown: Boolean,
        val dailyLimitReached: Boolean,
    )

    // Bank/service senders: "AX-HDFCBK", "VM-AMAZON", short codes like "56767", no-reply names.
    private val automatedSender = Regex("""^[A-Z]{2}-[A-Z0-9]{3,}(-[A-Z])?$|^\+?\d{3,6}$|no-?reply|notification""", RegexOption.IGNORE_CASE)

    fun decide(i: Input): AutoDecision {
        if (i.chatSetting == ChatSetting.OFF || !i.canReply || i.incoming.isEmpty()) return AutoDecision.None
        if (automatedSender.containsMatchIn(i.chatName.trim())) return AutoDecision.None
        val block = i.incoming.firstNotNullOfOrNull { SafetyFilter.blockReason(it) }
        // Nothing sensible to reply to a code or a password request: stay quiet.
        if (block == SafetyFilter.Reason.OTP || block == SafetyFilter.Reason.CREDENTIALS) return AutoDecision.None
        // The model only understands English: don't guess a reply to "Savu ninna".
        if (i.incoming.none { Language.looksEnglish(it) }) return AutoDecision.None

        val explicit = i.chatSetting == ChatSetting.AUTO || i.chatSetting == ChatSetting.MANUAL
        if (!explicit && i.isGroup && !i.includeGroups) return AutoDecision.None
        if (!explicit && !i.isWhatsApp && !i.includeOtherApps) return AutoDecision.None

        val wantAuto = i.chatSetting == ChatSetting.AUTO || (i.chatSetting == ChatSetting.DEFAULT && i.globalAuto)
        if (!wantAuto) {
            return if (i.proactiveSuggestions || i.chatSetting == ChatSetting.MANUAL) AutoDecision.Suggest else AutoDecision.None
        }
        return when {
            block != null -> AutoDecision.Hold(block.label)
            i.inCooldown -> AutoDecision.Hold("already auto-replied recently")
            i.dailyLimitReached -> AutoDecision.Hold("daily auto-reply limit reached")
            else -> AutoDecision.Auto
        }
    }
}
