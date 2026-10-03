package com.hy.assistant.screen

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hy.assistant.HyApp
import com.hy.assistant.MainActivity
import com.hy.assistant.core.ScreenNode
import com.hy.assistant.core.ScreenText
import com.hy.assistant.core.WhatsAppScreen
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.notifications.StoredMessage

/**
 * Reads on-screen text through Accessibility, only:
 *  - when the user presses the accessibility button / shortcut ("Ask Alfrid about this screen"), and
 *  - optionally, while a WhatsApp chat is open, to add its visible messages to Hy's chat history.
 * Password fields are never read. Nothing leaves the phone.
 */
class HyScreenService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var pendingWhatsApp: Runnable? = null
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        instance = this
        val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) = askAboutScreen()
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(cb)
        buttonCallback = cb
    }

    override fun onDestroy() {
        buttonCallback?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != WHATSAPP) return
        if (!HyApp.instance.settings.current.readWhatsAppScreen) return
        // Chats update often while scrolling: read once things settle.
        pendingWhatsApp?.let(main::removeCallbacks)
        val r = Runnable { readWhatsAppChat() }
        pendingWhatsApp = r
        main.postDelayed(r, 1200)
    }

    private fun readWhatsAppChat() {
        try {
            val root = appWindowRoot(WHATSAPP) ?: return
            val width = resources.displayMetrics.widthPixels
            val chat = WhatsAppScreen.parse(toScreenNode(root), width) ?: return
            val now = System.currentTimeMillis()
            // Keep on-screen order; times come from the bubble labels when readable.
            val msgs = chat.messages.mapIndexed { i, m ->
                val ts = WhatsAppScreen.timeToday(m.time)?.coerceAtMost(now) ?: (now - (chat.messages.size - i) * 1000L)
                StoredMessage(if (m.fromMe) "Me" else chat.name, m.text, ts + i, m.fromMe)
            }
            val added = MessageStore.mergeFromScreen(chat.name, msgs)
            if (added > 0) Log.i(TAG, "read $added new messages from the WhatsApp chat on screen")
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp screen read failed", e)
        }
    }

    /** Reads the app currently on screen and opens Hy with it. */
    fun askAboutScreen() {
        val app = appWindowRoot(null)
        if (app == null) {
            openHy("", "Screen", "Couldn't read this screen.")
            return
        }
        val pkg = app.packageName?.toString().orEmpty()
        val tree = toScreenNode(app)
        val text = ScreenText.toText(tree)
        lastDump = dump(tree)
        val label = "Screen · " + appLabel(pkg)
        openHy(text, label, if (text.isBlank()) "This screen has no readable text (it may be an image or a protected app)." else null)
    }

    private fun openHy(text: String, label: String, error: String?) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SCREEN_TEXT, text)
            .putExtra(MainActivity.EXTRA_SCREEN_LABEL, label)
            .putExtra(MainActivity.EXTRA_SCREEN_ERROR, error)
        startActivity(i)
    }

    /** Root of the app window on screen (skips the status bar, keyboard and Hy itself). */
    private fun appWindowRoot(pkg: String?): AccessibilityNodeInfo? {
        val candidates = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.isActive || it.isFocused }
        for (w in candidates) {
            val root = w.root ?: continue
            val p = root.packageName?.toString() ?: continue
            if (p == packageName) continue
            if (pkg == null || p == pkg) return root
        }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName && (pkg == null || it.packageName == pkg) }
    }

    private fun toScreenNode(n: AccessibilityNodeInfo, depth: Int = 0, budget: IntArray = intArrayOf(3000)): ScreenNode {
        val r = Rect().also(n::getBoundsInScreen)
        val kids = mutableListOf<ScreenNode>()
        if (depth < 40) {
            for (i in 0 until n.childCount) {
                if (budget[0]-- <= 0) break
                val c = n.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                kids += toScreenNode(c, depth + 1, budget)
            }
        }
        return ScreenNode(
            viewId = n.viewIdResourceName,
            text = if (n.isPassword) null else n.text?.toString(),
            desc = n.contentDescription?.toString(),
            className = n.className?.toString(),
            isPassword = n.isPassword,
            left = r.left, top = r.top, right = r.right, bottom = r.bottom,
            children = kids,
        )
    }

    private fun dump(root: ScreenNode): String = buildString {
        fun walk(n: ScreenNode, d: Int) {
            if (n.isPassword) return
            if (n.viewId != null || !n.text.isNullOrBlank() || !n.desc.isNullOrBlank()) {
                append("  ".repeat(d.coerceAtMost(12))).append(n.viewId ?: "-").append(" [")
                    .append(n.left).append(',').append(n.top).append(',').append(n.right).append(',').append(n.bottom).append("] ")
                    .append(n.text ?: "").append(if (!n.desc.isNullOrBlank()) " (${n.desc})" else "").append('\n')
            }
            n.children.forEach { walk(it, d + 1) }
        }
        walk(root, 0)
    }.take(20_000)

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    companion object {
        private const val TAG = "HyScreen"
        const val WHATSAPP = "com.whatsapp"

        @Volatile
        var instance: HyScreenService? = null
            private set

        /** Structure of the last screen read (for "Copy screen dump" when tuning the reader). */
        @Volatile
        var lastDump: String = ""
            private set

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, HyScreenService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
