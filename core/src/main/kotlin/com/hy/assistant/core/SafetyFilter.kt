package com.hy.assistant.core

/**
 * Decides whether an incoming message is safe to answer automatically. Anything touching
 * codes, money, credentials or emergencies always goes to the user instead.
 */
object SafetyFilter {
    enum class Reason(val label: String) {
        OTP("one-time code"),
        MONEY("money / payment"),
        CREDENTIALS("password or PIN"),
        EMERGENCY("urgent / emergency"),
    }

    private val otp = Regex("""\b(otp|one[- ]time|verification code|security code|login code|code is|passcode)\b|\b\d{4,8}\b.*\b(code|otp)\b|\b(code|otp)\b.*\b\d{4,8}\b""")
    private val money = Regex(
        """(₹|\$|€|£|\brs\.?\s?\d|\binr\b|\bupi\b|\bpayment\b|\bpay me\b|\bpay\b.*\b(back|now|today)\b|\bsend (me )?money\b|\btransfer\b|\bbank\b|\baccount number\b|\bifsc\b|\bloan\b|\blend me\b|\bborrow\b|\bgpay\b|\bpaytm\b|\bphonepe\b)""",
    )
    private val credentials = Regex("""\b(password|passcode|\bpin\b|cvv|card number|login details|credentials)\b""")
    private val emergency = Regex("""\b(urgent|emergency|hospital|accident|ambulance|police|help me|call me asap|asap)\b""")

    /** Returns why the message must not be auto-answered, or null if it's fine. */
    fun blockReason(text: String): Reason? {
        val t = text.lowercase()
        return when {
            otp.containsMatchIn(t) -> Reason.OTP
            credentials.containsMatchIn(t) -> Reason.CREDENTIALS
            money.containsMatchIn(t) -> Reason.MONEY
            emergency.containsMatchIn(t) -> Reason.EMERGENCY
            else -> null
        }
    }
}

/** Rule-based category for non-chat notifications (no LLM needed). */
object NotificationClassifier {
    enum class Category(val label: String) {
        OTP("Codes"),
        PAYMENT("Payments"),
        DELIVERY("Deliveries"),
        CALENDAR("Reminders"),
        SOCIAL("Social"),
        PROMO("Offers"),
        OTHER("Other"),
    }

    private val otp = Regex("""\b(otp|verification code|one[- ]time password|security code|login code)\b|\bcode\b.*\b\d{4,8}\b|\b\d{4,8}\b.*\bcode\b""")
    private val payment = Regex("""(₹|\brs\.?\s?\d|\binr\b|\bupi\b|debited|credited|payment|paid|refund|transaction|a/c|\bbill\b|wallet)""")
    private val delivery = Regex("""\b(delivered|out for delivery|shipped|dispatched|order|package|parcel|courier|arriving|your ride|driver)\b""")
    private val calendar = Regex("""\b(reminder|meeting|event|alarm|appointment|calendar|starts in|scheduled)\b""")
    private val promo = Regex("""\b(offer|sale|% off|discount|deal|cashback|coupon|limited time|flat \d+|free shipping)\b""")

    private val socialPackages = setOf(
        "com.instagram.android", "com.facebook.katana", "com.twitter.android", "com.snapchat.android",
        "com.linkedin.android", "com.google.android.youtube", "com.reddit.frontpage",
    )

    fun classify(packageName: String, title: String, text: String): Category {
        val t = "$title $text".lowercase()
        return when {
            otp.containsMatchIn(t) -> Category.OTP
            payment.containsMatchIn(t) -> Category.PAYMENT
            delivery.containsMatchIn(t) -> Category.DELIVERY
            calendar.containsMatchIn(t) -> Category.CALENDAR
            promo.containsMatchIn(t) -> Category.PROMO
            packageName in socialPackages -> Category.SOCIAL
            else -> Category.OTHER
        }
    }
}
