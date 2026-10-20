package com.hy.assistant.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.hy.assistant.HyApp
import com.hy.assistant.auto.ActivityLog
import com.hy.assistant.auto.ReplyStyle
import com.hy.assistant.core.ContactMatcher
import com.hy.assistant.core.PhoneAction
import com.hy.assistant.core.PhoneAction.Kind
import com.hy.assistant.core.PhoneActions
import com.hy.assistant.core.Prompts
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.screen.HyScreenService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Runs phone actions. Most are single Android intents (reliable, no screen control). WhatsApp
 * opens the chat, reads it, drafts a reply, types it, and waits for the user's Send on Hy's
 * on-screen panel: nothing is sent without that tap.
 */
object PhoneActionRunner {
    sealed interface Outcome {
        data class Done(val message: String) : Outcome
        data class Failed(val message: String) : Outcome
        /** Contacts permission needed; ask, then run the same action again. */
        data object NeedContacts : Outcome
    }

    suspend fun run(context: Context, a: PhoneAction): Outcome = try {
        when (a.kind) {
            Kind.OPEN_APP -> openApp(context, a.target)
            Kind.WHATSAPP -> whatsApp(context, a.target, a.text)
            Kind.SMS -> withContact(context, a.target) { c ->
                start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", a.text))
                Outcome.Done("Opened Messages for ${c.name}${if (a.text.isNotBlank()) " with your text" else ""}. Tap send there.")
            }
            Kind.CALL -> withContact(context, a.target) { c ->
                start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.number))))
                Outcome.Done("Dialer open for ${c.name}. Tap call.")
            }
            Kind.ALARM -> {
                val (h, m) = PhoneActions.parseClock(a.target) ?: return Outcome.Failed("I couldn't read the time \"${a.target}\".")
                start(
                    context,
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Hy").putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                )
                Outcome.Done("Alarm set for %d:%02d %s.".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM"))
            }
            Kind.TIMER -> {
                val sec = PhoneActions.parseDurationSeconds(a.target) ?: return Outcome.Failed("I couldn't read \"${a.target}\" as a duration.")
                start(context, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                Outcome.Done("Timer started: ${a.target}.")
            }
            Kind.NAVIGATE -> {
                val q = Uri.encode(a.target)
                if (!tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")))) {
                    start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
                }
                Outcome.Done("Navigation to ${a.target} started.")
            }
            Kind.YOUTUBE -> {
                val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", a.target)
                if (!tryStart(context, yt)) {
                    start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com/results?search_query=" + URLEncoder.encode(a.target, "UTF-8"))))
                }
                Outcome.Done("Searching YouTube for ${a.target}.")
            }
        }
    } catch (e: ActivityNotFoundException) {
        Outcome.Failed("No app on this phone can do that.")
    }

    // ---- Apps ----------------------------------------------------------------------------

    private fun openApp(context: Context, name: String): Outcome {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associateBy { it.loadLabel(pm).toString() }
        return when (val r = ContactMatcher.resolve(name, apps.keys)) {
            is ContactMatcher.Result.Unique -> {
                val info = apps.getValue(r.name).activityInfo
                start(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(info.packageName, info.name))
                Outcome.Done("Opened ${r.name}.")
            }
            is ContactMatcher.Result.Ambiguous -> Outcome.Failed("Which app: ${r.candidates.joinToString(", ")}?")
            ContactMatcher.Result.NotFound -> Outcome.Failed("I couldn't find an app called \"$name\".")
        }
    }

    // ---- WhatsApp --------------------------------------------------------------------------

    private suspend fun whatsApp(context: Context, target: String, text: String): Outcome {
        val screen = HyScreenService.instance
            ?: return Outcome.Failed("Turn on Screen reading first (Settings → Screen reading). Hy needs it to read the chat and type the reply.")
        val pkg = whatsAppPackage(context) ?: return Outcome.Failed("WhatsApp isn't installed.")
        return withContact(context, target) { c ->
            val number = PhoneActions.whatsAppNumber(c.number, Contacts.defaultCountryCode(context))
                ?: return@withContact Outcome.Failed("${c.name}'s number (${c.number}) doesn't look like a WhatsApp number.")
            screen.showPanel("Hy", "Opening ${c.name} on WhatsApp…")
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")).setPackage(pkg))

            val chat = screen.waitForWhatsAppChat(pkg, 10_000)
                ?: return@withContact screen.fail("Couldn't open the chat with ${c.name}. Is that number on WhatsApp?")
            screen.showPanel("Hy", "Reading the chat and drafting a reply…")
            val stored = MessageStore.state.value.firstOrNull { it.name.equals(chat.name, ignoreCase = true) && it.isWhatsApp }
            val s = HyApp.instance.settings.current
            val model = HyApp.instance.models.activeModelFile()

            val draft = when {
                text.isNotBlank() && (!s.polishReplies || model == null) -> text
                model == null -> return@withContact screen.fail("No AI model installed, so I can't draft a reply. Download one in Models.")
                else -> {
                    val lines = stored?.messages?.map { it.toChatLine() }.orEmpty()
                    val prompt = Prompts.draftReply(
                        chat.name, lines, s.userName, s.tone, gist = text.ifBlank { null },
                        styleRules = stored?.let { ReplyStyle.promptRules(it, s) }.orEmpty(),
                    )
                    val raw = withContext(Dispatchers.Default) { HyApp.instance.engine.complete(model, prompt, s.threads, s.contextSize) }
                    ReplyStyle.finish(raw, stored, s).ifBlank { text }
                }
            }
            if (draft.isBlank()) return@withContact screen.fail("I couldn't write a reply for this chat.")

            if (!screen.typeIntoWhatsApp(pkg, draft)) {
                return@withContact screen.fail("Couldn't type into WhatsApp. Draft: \"$draft\"", copyText = draft)
            }
            // Option A: wait for the user's tap on Send in Hy's panel.
            when (screen.askToSend("Send to ${c.name}?", draft)) {
                true -> if (screen.pressWhatsAppSend(pkg)) {
                    ActivityLog.add(ActivityLog.Kind.SENT, c.name, draft, "typed in WhatsApp")
                    screen.flashPanel("Sent to ${c.name} ✓")
                    Outcome.Done("Sent to ${c.name}: \"$draft\"")
                } else {
                    screen.fail("The draft is in WhatsApp's message box, but I couldn't press Send. Tap it yourself.")
                }
                false -> {
                    screen.hidePanel()
                    Outcome.Done("Draft left in WhatsApp's message box for ${c.name}. Edit or send it yourself.")
                }
            }
        }
    }

    /** Shows the problem on Hy's panel over WhatsApp (with Copy when there's a draft) and reports it. */
    private suspend fun HyScreenService.fail(message: String, copyText: String? = null): Outcome {
        showMessage(message, copyText)
        return Outcome.Failed(message)
    }

    private fun whatsAppPackage(context: Context): String? =
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { p ->
            runCatching { context.packageManager.getPackageInfo(p, 0) }.isSuccess
        }

    // ---- Helpers ----------------------------------------------------------------------------

    private inline fun withContact(context: Context, name: String, block: (Contacts.Contact) -> Outcome): Outcome =
        when (val r = Contacts.find(context, name)) {
            is Contacts.Result.Found -> block(r.contact)
            is Contacts.Result.Ambiguous -> Outcome.Failed("Which ${name}: ${r.names.joinToString(", ")}? Say the full name.")
            Contacts.Result.NotFound -> Outcome.Failed("No contact called \"$name\" on this phone.")
            Contacts.Result.NoPermission -> Outcome.NeedContacts
        }

    private fun start(context: Context, intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        start(context, intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
