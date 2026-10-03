package com.hy.assistant.core

import com.hy.assistant.core.AutomationPolicy.ChatSetting
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationPolicyTest {
    private val base = AutomationPolicy.Input(
        chatName = "Rahul", isWhatsApp = true, isGroup = false, canReply = true, incoming = listOf("are you coming?"),
        chatSetting = ChatSetting.DEFAULT, globalAuto = false, proactiveSuggestions = true,
        includeGroups = false, includeOtherApps = false, inCooldown = false, dailyLimitReached = false,
    )

    private fun d(i: AutomationPolicy.Input) = AutomationPolicy.decide(i)

    @Test
    fun manualSuggestsForNormalWhatsAppChat() {
        assertEquals(AutoDecision.Suggest, d(base))
        assertEquals(AutoDecision.None, d(base.copy(proactiveSuggestions = false)))
    }

    @Test
    fun autoSendsUnlessHeld() {
        val auto = base.copy(globalAuto = true)
        assertEquals(AutoDecision.Auto, d(auto))
        assertEquals(AutoDecision.Hold("money / payment"), d(auto.copy(incoming = listOf("send me 500 on gpay"))))
        assertEquals(AutoDecision.Hold("already auto-replied recently"), d(auto.copy(inCooldown = true)))
        assertEquals(AutoDecision.Hold("daily auto-reply limit reached"), d(auto.copy(dailyLimitReached = true)))
    }

    @Test
    fun staysQuietForCodesBotsAndDeadReplies() {
        assertEquals(AutoDecision.None, d(base.copy(incoming = listOf("482913 is your OTP"))))
        assertEquals(AutoDecision.None, d(base.copy(incoming = listOf("what's the wifi password"))))
        assertEquals(AutoDecision.None, d(base.copy(chatName = "AX-HDFCBK")))
        assertEquals(AutoDecision.None, d(base.copy(chatName = "56767")))
        assertEquals(AutoDecision.None, d(base.copy(canReply = false)))
        assertEquals(AutoDecision.None, d(base.copy(chatSetting = ChatSetting.OFF)))
        assertEquals(AutoDecision.None, d(base.copy(globalAuto = true, incoming = listOf("Poya", "Savu ninna"))))
        assertEquals(AutoDecision.None, d(base.copy(incoming = listOf("नमस्ते कैसे हो"))))
        assertEquals(AutoDecision.Auto, d(base.copy(globalAuto = true, incoming = listOf("Savu ninna", "what are you doing"))))
        assertEquals(AutoDecision.Auto, d(base.copy(globalAuto = true, incoming = listOf("👍"))))
    }

    @Test
    fun groupsAndOtherAppsNeedOptInOrExplicitChatSetting() {
        assertEquals(AutoDecision.None, d(base.copy(isGroup = true)))
        assertEquals(AutoDecision.Suggest, d(base.copy(isGroup = true, includeGroups = true)))
        assertEquals(AutoDecision.Suggest, d(base.copy(isGroup = true, chatSetting = ChatSetting.MANUAL)))
        assertEquals(AutoDecision.Auto, d(base.copy(isGroup = true, chatSetting = ChatSetting.AUTO)))
        assertEquals(AutoDecision.None, d(base.copy(isWhatsApp = false)))
        assertEquals(AutoDecision.Suggest, d(base.copy(isWhatsApp = false, includeOtherApps = true)))
    }
}
