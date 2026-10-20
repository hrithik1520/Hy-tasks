package com.hy.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design review renders, written to app/build/ui-renders when run with
 * `./gradlew :app:testDebugUnitTest -Phy.renderUi`. Not part of CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class DesignSnapshots {
    @get:Rule
    val compose = createComposeRule()

    /** Renders [content] after [ms] of animation time (the clock is paused: the orb animates forever). */
    private fun render(name: String, ms: Long = 700, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent(content)
        compose.mainClock.advanceTimeBy(ms)
        compose.onRoot().captureRoboImage("build/ui-renders/$name.png")
    }

    @Test
    fun components() = render("components") {
        HyTheme(darkTheme = true) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in OrbMode.entries) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HyOrb(m, size = 88.dp, level = 0.6f)
                        Text(m.name, style = DotLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SlidingPill(listOf("Manual", "Auto"), selected = 1, onSelect = {})
                SlidingPill(listOf("Manual", "Auto"), selected = 0, onSelect = {})
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("AI ready", dot = NothingRed, pulsing = true)
                    StatusChip("Screen reading", dot = Color(0xFF4CAF50))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile("✦", "What did I miss?", {}, Modifier.width(110.dp), active = true)
                    QuickTile("◎", "Browser", {}, Modifier.width(110.dp))
                    QuickTile(">_", "Terminal", {}, Modifier.width(110.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("Rahul Sharma", unread = true)
                    Avatar("Mom", unread = false)
                    Avatar("Priya", unread = false)
                    TypingDots()
                }
                SectionHeader("Chats")
                Text("GOOD EVENING", style = DotLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Hrithik", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
                Box(Modifier.height(80.dp).width(300.dp)) { DotGrid(Modifier.fillMaxSize()) }
            }
        }
    }

    private val now = System.currentTimeMillis()
    private fun chat(name: String, last: String, unread: Int, minsAgo: Int, app: String = "WhatsApp", pkg: String = "com.whatsapp") = com.hy.assistant.notifications.Chat(
        key = name, name = name, packageName = pkg, appName = app, isGroup = false,
        messages = listOf(com.hy.assistant.notifications.StoredMessage(name, last, now - minsAgo * 60_000L, false)),
        lastReadAt = if (unread > 0) 0L else now, canReply = true,
    )

    /** The home screen, assembled from the same parts HomeScreen uses, with sample data. */
    @Composable
    private fun HomeMock(dark: Boolean, conversation: Boolean, setup: Boolean = false) = HyTheme(darkTheme = dark) {
        androidx.compose.material3.Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            bottomBar = { InputBar("", {}, {}, {}, if (conversation) "Ask a follow-up…" else "Ask Alfred anything…", listening = false) },
        ) { pad ->
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    HomeHeader(
                        name = "Hrithik", auto = conversation, onModeChange = {},
                        aiLabel = if (conversation) "Thinking" else "AI ready", aiDot = if (conversation) NothingRed else MaterialTheme.colorScheme.onSurface,
                        aiPulsing = conversation, modeHint = if (conversation) "Replies by itself after 10s. Never for codes, money, passwords or emergencies."
                        else "Drafts a reply for each new message. Tap Send in the notification.",
                        onSettings = {}, greeting = "Good evening",
                    )
                }
                item {
                    OrbHero(if (conversation) OrbMode.THINKING else OrbMode.IDLE, 0f,
                        if (conversation) "Working on it" else "Tap to talk", if (conversation) "" else "or say \"Hey Alfred\"", {}, compact = conversation)
                }
                if (setup) {
                    item { SetupCard("Step 1", "Allow notification access", "Hy reads your notifications on this phone to summarize and reply. Nothing leaves the device.", "Open settings") {} }
                    item { SetupCard("Step 2", "Get the on-device AI model", "One-time ~1.1 GB download. Needed for suggestions and auto-replies.", "Choose model") {} }
                }
                if (conversation) {
                    item { SectionHeader("Conversation") { androidx.compose.material3.TextButton({}) { Text("New chat", color = NothingRed) } } }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChatBubble("What did I miss?", fromUser = true)
                            ChatBubble("Rahul is asking if you're coming tonight at 8. Mom wants you to call her. Your Amazon package was delivered.", fromUser = false)
                            ChatBubble("Reply to Rahul saying I'll be there", fromUser = true)
                            TypingBubble()
                        }
                    }
                }
                item {
                    Column {
                        SectionHeader("Quick actions")
                        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                        QuickActions(agentMode = false, {}, {}, {}, {}, {})
                    }
                }
                item { SectionHeader("Hy activity") }
                item { ActivityLine(com.hy.assistant.auto.ActivityLog.Entry(now - 300_000, com.hy.assistant.auto.ActivityLog.Kind.AUTO_SENT, "Priya", "Thank you so much! 😊")) }
                item { SectionHeader("Chats · last 3 days") }
                item { ChatRow(chat("Rahul Sharma", "bro are you coming tonight?", 2, 3)) {} }
                item { ChatRow(chat("Mom", "Call me when you're free", 1, 25)) {} }
                item { ChatRow(chat("College Gang", "Priya: haha 😂", 0, 90)) {} }
                item { ChatRow(chat("Office", "Meeting moved to 4", 0, 300, app = "Telegram", pkg = "org.telegram.messenger")) {} }
                item { SectionHeader("Other notifications · 24 h") }
                item {
                    FeedLine(com.hy.assistant.notifications.FeedItem("1", "in.amazon", "Amazon", "Delivered", "Your package was delivered to the front door",
                        now - 3_600_000, com.hy.assistant.core.NotificationClassifier.Category.DELIVERY))
                }
            }
        }
    }

    @Test fun homeDark() = render("home-dark") { HomeMock(dark = true, conversation = false) }
    @Test fun homeConversation() = render("home-conversation") { HomeMock(dark = true, conversation = true) }
    @Test fun homeLight() = render("home-light") { HomeMock(dark = false, conversation = false) }
    @Test fun homeFirstRun() = render("home-first-run") { HomeMock(dark = true, conversation = false, setup = true) }
}
