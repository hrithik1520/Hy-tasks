package com.hy.assistant.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small card Hy shows over other apps (TYPE_ACCESSIBILITY_OVERLAY) while it works in WhatsApp:
 * progress, the drafted reply, and the Send / Cancel buttons. It doesn't take keyboard focus, so
 * the user can still edit the draft directly in WhatsApp's message box.
 */
class HyPanel(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

    /** Must be called on the main thread. */
    fun show(title: String, body: String, buttons: List<Pair<String, () -> Unit>> = emptyList()) {
        hide()
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F21C1F23"))
            }
            elevation = dp(8).toFloat()
        }
        card.addView(TextView(service).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            paint.isFakeBoldText = true
        })
        card.addView(TextView(service).apply {
            text = body
            setTextColor(Color.parseColor("#E6E6E6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(6))
            maxLines = 8
        })
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(service).apply { gravity = Gravity.END }
            for ((label, onClick) in buttons) {
                row.addView(Button(service).apply {
                    text = label
                    isAllCaps = false
                    setTextColor(if (label == "Send") Color.WHITE else Color.parseColor("#FFB4B4B4"))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (label == "Send") Color.parseColor("#FFD71921") else Color.TRANSPARENT)
                    }
                    setPadding(dp(18), 0, dp(18), 0)
                    setOnClickListener {
                        hide()
                        onClick()
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
            }
            card.addView(row)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Top of the screen, below the chat header, so WhatsApp's message box stays visible.
            gravity = Gravity.TOP
            y = dp(96)
            horizontalMargin = 0.03f
        }
        runCatching { wm.addView(card, lp) }.onSuccess { view = card }
    }

    fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
    }
}
