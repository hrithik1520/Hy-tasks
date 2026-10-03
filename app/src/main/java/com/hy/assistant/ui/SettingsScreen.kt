package com.hy.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hy.assistant.MainViewModel
import com.hy.assistant.core.Tone
import com.hy.assistant.auto.ActivityLog
import com.hy.assistant.auto.BriefingWorker
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hy.assistant.memory.MemoryStore
import com.hy.assistant.tools.SearchEngine
import com.hy.assistant.notifications.MessageStore
import androidx.compose.material3.TextButton
import com.hy.assistant.notifications.NotificationFeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val s by vm.settings.data.collectAsState()
    val facts by vm.memoryFacts.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val screenOn = remember(resumeTick) { com.hy.assistant.screen.HyScreenService.isEnabled(context) }
    var showDisclosure by remember { mutableStateOf(false) }
    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Let Hy read your screen?") },
            text = {
                Text(
                    "Hy uses Android's Accessibility service to read the text on your screen:\n\n" +
                        "• only when you tap the accessibility button, or in open WhatsApp chats if you turn that on\n" +
                        "• never password fields\n" +
                        "• everything stays on this phone, nothing is uploaded\n\n" +
                        "Hy doesn't tap, type or control other apps. On the next screen, open \"Hy · read screen\" and turn it on.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("Not now") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text("About you", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = s.userName,
                    onValueChange = { v -> vm.settings.update { it.copy(userName = v) } },
                    label = { Text("Your name (used when drafting replies)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(4.dp))
                Text("Reply tone", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tone.entries.forEach { t ->
                        FilterChip(
                            selected = s.tone == t,
                            onClick = { vm.settings.update { it.copy(tone = t) } },
                            label = { Text(t.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                        )
                    }
                }
                ToggleRow(
                    "Humanize replies",
                    "Replies sound like you texting, not a chatbot: no \"Certainly!\" or \"I hope this helps\", no em dashes or fancy words, and matched to how you write in each chat (length, lowercase, emojis).",
                    s.humanizeReplies,
                ) { v -> vm.settings.update { it.copy(humanizeReplies = v) } }
                ToggleRow("Polish my replies with AI", "\"Reply to X saying …\" gets rewritten naturally. Off = sends your exact words.", s.polishReplies) { v ->
                    vm.settings.update { it.copy(polishReplies = v) }
                }
            }

            SectionCard {
                Text("Automation", style = MaterialTheme.typography.titleSmall)
                ToggleRow(
                    "Suggest replies automatically",
                    "Manual mode: draft a reply for every new message and show it as a notification with Send.",
                    s.proactiveSuggestions,
                ) { v -> vm.settings.update { it.copy(proactiveSuggestions = v) } }
                Spacer(Modifier.padding(4.dp))
                Text("Auto mode: wait before sending", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Instant", 10 to "10 s", 30 to "30 s").forEach { (sec, label) ->
                        FilterChip(selected = s.autoSendDelaySec == sec, onClick = { vm.settings.update { it.copy(autoSendDelaySec = sec) } }, label = { Text(label) })
                    }
                }
                Text(
                    "The delay gives you a Cancel button before anything is sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Text("At most one auto-reply per chat every", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 5, 15).forEach { m ->
                        FilterChip(selected = s.autoCooldownMin == m, onClick = { vm.settings.update { it.copy(autoCooldownMin = m) } }, label = { Text("$m min") })
                    }
                }
                ToggleRow("Include group chats", "Suggestions and auto-replies in groups. Off by default (busy groups = lots of drafts). Per-chat Auto/Manual still works.", s.autoReplyGroups) { v ->
                    vm.settings.update { it.copy(autoReplyGroups = v) }
                }
                ToggleRow("Include other messengers", "Telegram, Messages, etc. (WhatsApp is always included). Bank/OTP senders are always skipped.", s.autoReplyOtherApps) { v ->
                    vm.settings.update { it.copy(autoReplyOtherApps = v) }
                }
                ToggleRow("Add \"— sent by my assistant\"", "Lets people know an auto-reply wasn't typed by you.", s.appendSignature) { v ->
                    vm.settings.update { it.copy(appendSignature = v) }
                }
                Text(
                    "Always held for you (never auto-sent): OTPs/codes, money & payments, passwords/PINs, emergencies. " +
                        "Per-chat Auto/Manual/Off is on each chat's screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                Text("Memory", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tell Hy \"remember that …\" on the home screen. Used in answers and in reply drafts you review — " +
                        "never in Auto replies. Passwords, PINs and codes are refused. Stored only on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (facts.isEmpty()) {
                    Text("Nothing remembered yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
                facts.sortedByDescending { it.createdAt }.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• ${f.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { MemoryStore.remove(f.id) }) { Text("Delete") }
                    }
                }
                Spacer(Modifier.padding(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { MemoryStore.clear() }, enabled = facts.isNotEmpty()) { Text("Forget everything") }
                    OutlinedButton(onClick = { vm.newChat() }) { Text("Clear conversation") }
                }
            }

            SectionCard {
                Text("Tools", style = MaterialTheme.typography.titleSmall)
                ToggleRow(
                    "Web search",
                    "Let Hy look up facts it doesn't know (Bing / DuckDuckGo / Wikipedia). Only the search words leave the phone — never your messages.",
                    s.webSearch,
                ) { v -> vm.settings.update { it.copy(webSearch = v) } }
                Text("Search engine", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchEngine.entries.forEach { e ->
                        FilterChip(selected = s.searchEngine == e, onClick = { vm.settings.update { it.copy(searchEngine = e) } }, label = { Text(e.label) })
                    }
                }
                Text(
                    if (s.searchEngine == SearchEngine.GOOGLE) {
                        "Reads Google's results page in a hidden browser. If Google asks \"I'm not a robot\", a popup lets you solve it. " +
                            "Falls back to Bing if it fails. Google's terms don't allow automated searches — keep it to personal, light use."
                    } else "Free, no key needed. Falls back to DuckDuckGo, then Wikipedia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Text("Agent: max steps per task", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15).forEach { n ->
                        FilterChip(selected = s.agentMaxSteps == n, onClick = { vm.settings.update { it.copy(agentMaxSteps = n) } }, label = { Text("$n") })
                    }
                }
                Text(
                    "More steps = harder tasks, but slower (each step is several seconds on the phone).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Terminal commands suggested by Hy never run until you tap Run. Incoming messages can't trigger searches or commands.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                Text("Screen reading", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (screenOn) "On. Tap the accessibility button (or your accessibility shortcut) on any screen to ask Hy about it."
                    else "Off. Lets Hy read the text on your screen when you ask, like Gemini's \"Ask about screen\".",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.padding(4.dp))
                if (!screenOn) {
                    OutlinedButton(onClick = { showDisclosure = true }) { Text("Turn on…") }
                } else {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Accessibility settings") }
                }
                ToggleRow(
                    "Read open WhatsApp chats (experimental)",
                    "While a WhatsApp chat is open, Hy adds the messages you can see to its history, so summaries and replies see the whole conversation, not just notifications.",
                    s.readWhatsAppScreen,
                ) { v -> vm.settings.update { it.copy(readWhatsAppScreen = v) } }
                if (screenOn) {
                    TextButton(onClick = {
                        val dump = com.hy.assistant.screen.HyScreenService.lastDump
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Hy screen dump", dump.ifBlank { "(press the accessibility button on a screen first)" }))
                    }) { Text("Copy last screen dump (for fixing the reader)") }
                }
                Text(
                    "Never reads password fields. Reads only when you press the button, or WhatsApp chats if the switch is on. Nothing leaves the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                Text("Daily briefing", style = MaterialTheme.typography.titleSmall)
                ToggleRow("Morning briefing", "A daily notification with your unread chats and important payments, deliveries and reminders.", s.briefingEnabled) { v ->
                    vm.settings.update { it.copy(briefingEnabled = v) }
                    BriefingWorker.schedule(context, v, s.briefingHour)
                }
                if (s.briefingEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6, 7, 8, 9, 21).forEach { h ->
                            FilterChip(
                                selected = s.briefingHour == h,
                                onClick = { vm.settings.update { it.copy(briefingHour = h) }; BriefingWorker.schedule(context, true, h) },
                                label = { Text(if (h < 12) "$h AM" else "${h - 12} PM") },
                            )
                        }
                    }
                }
            }

            SectionCard {
                Text("Notifications Hy watches", style = MaterialTheme.typography.titleSmall)
                ToggleRow("Watch all apps", "Chats from any messenger + a feed of other notifications (codes, deliveries, payments…).", s.watchAllApps) { v ->
                    vm.settings.update { it.copy(watchAllApps = v) }
                }
                ToggleRow("Include WhatsApp Business", null, s.includeBusiness) { v ->
                    vm.settings.update { it.copy(includeBusiness = v) }
                }
                Spacer(Modifier.padding(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { MessageStore.clearAll(); NotificationFeed.clear() }) { Text("Clear messages") }
                    OutlinedButton(onClick = { ActivityLog.clear() }) { Text("Clear activity") }
                }
                Text(
                    "Messages are kept only on this phone for 3 days, other notifications for 24 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                Text("Performance", style = MaterialTheme.typography.titleSmall)
                Text("CPU threads: ${s.threads}", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4, 6).forEach { n ->
                        FilterChip(selected = s.threads == n, onClick = { vm.settings.update { it.copy(threads = n) } }, label = { Text("$n") })
                    }
                }
                Text(
                    "4 is best on Nothing Phone (3a) Pro (uses the fast cores).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Text("Context size: ${s.contextSize} tokens", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 4096).forEach { n ->
                        FilterChip(selected = s.contextSize == n, onClick = { vm.settings.update { it.copy(contextSize = n) } }, label = { Text("$n") })
                    }
                }
            }

            Text(
                "Hy Assistant · all AI runs on-device · no account, no cloud. Web search sends only the query.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
