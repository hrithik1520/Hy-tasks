package com.hy.assistant.core

/**
 * Decides whether an incoming message is safe to answer automatically. Anything touching
 * distress, emergencies, codes, credentials, money or personal details always goes to the
 * user instead. Deliberately over-cautious: a false "hold" costs one tap, a miss can cost more.
 */
object SafetyFilter {
    /** Declared in priority order: when several apply, the earliest is reported. */
    enum class Reason(val label: String, val neverDraft: Boolean = false) {
        /** Self-harm talk: no bot-written reply is appropriate, in any mode. */
        DISTRESS("someone may be in distress", neverDraft = true),
        EMERGENCY("urgent / emergency"),
        OTP("one-time code"),
        CREDENTIALS("password, PIN or ID number"),
        MONEY("money / payment"),
        PRIVACY("location / personal details"),
    }

    private val distress = Regex(
        """\b(kill(ing)? myself|suicid\w*|end(ing)? (my life|it all)|want(ed)? to die|wanna die|(hurt|harm|cut)(ting)? myself|self[- ]?harm|don't want to (live|be alive|be here)|no reason to live|can't go on|better off (dead|without me)|take my (own )?life)\b""",
    )
    private val emergency = Regex(
        """\b(urgent(ly)?|emergenc(y|ies)|asap|help me|please help|need help|in trouble|in danger|not safe|unsafe|scared|accident|hospital|icu|ambulance|police|bleeding|injured|fire|kidnap\w*|robbed|attacked)\b""",
    )
    private val otp = Regex(
        """\b(otp|one[- ]time|verification code|security code|login code|auth(entication)? code|2fa|code is|passcode)\b|\b(send|share|tell|give|forward|read out) (me )?(the |that |your |ur )?(codes?|otp)\b|\b\d{4,8}\b.*\b(code|otp)\b|\b(code|otp)\b.*\b\d{4,8}\b""",
    )
    private val credentials = Regex(
        """\b(password|passwd|pwd|passcode|pin|cvv|cvc|card number|card details|login details|credentials|aadhaar|aadhar|pan card|pan number|ssn|social security|passport number|seed phrase|private key|secret key|api key|account number|ifsc|upi id)\b""",
    )
    private val money = Regex(
        """[₹$€£]|\b(rs|inr|usd|eur|rupees?|dollars?|bucks|euros?)\b|\b\d[\d,.]*\s?(k|rs|inr|usd|rupees?|dollars?|bucks)\b|\b(upi|gpay|google pay|paytm|phonepe|bhim|venmo|zelle|cash ?app|paypal|neft|imps|rtgs|net ?banking)\b|\b(pay|pays|paid|paying|payment|payments|transfer|transferred|refund|loan|lend|borrow|owe|owed|debt|emi|rent|salary|invoice|cash|money|deposit|bank)\b""",
    )
    private val privacy = Regex(
        """\b(send|share|tell|give|drop|show) (me )?(your|ur|the) (live )?(location|address|number|phone number|email|photos?|pics?|selfie)\b|\bshare (your )?(live )?location\b|\bwhere (do you|u) live\b|\b(your|ur) (home |house )?address\b""",
    )

    private val rules: List<Pair<Reason, Regex>> = listOf(
        Reason.DISTRESS to distress,
        Reason.EMERGENCY to emergency,
        Reason.OTP to otp,
        Reason.CREDENTIALS to credentials,
        Reason.MONEY to money,
        Reason.PRIVACY to privacy,
    )

    /** Lowercases and straightens curly apostrophes ("can\u2019t" -> "can't"). */
    internal fun normalize(text: String): String =
        text.lowercase().replace('\u2019', '\'').replace('\u2018', '\'').replace('`', '\'')

    /** Returns why the message must not be auto-answered, or null if it's fine. */
    fun blockReason(text: String): Reason? {
        val t = normalize(text)
        return rules.firstOrNull { (_, re) -> re.containsMatchIn(t) }?.first
    }

    /** The highest-priority reason across several messages, or null. */
    fun worstReason(texts: List<String>): Reason? =
        texts.mapNotNull { blockReason(it) }.minByOrNull { it.ordinal }
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
