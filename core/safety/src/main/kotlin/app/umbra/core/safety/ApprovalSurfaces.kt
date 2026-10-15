package app.umbra.core.safety

/** Where the user answers an approval. Each place shows a different amount of context. */
enum class ApprovalSurface {
    /** The notification shade: one line of text, answerable without unlocking the phone. */
    NOTIFICATION,

    /** The Capsule over another app: the description and the risk, without the session around it. */
    CAPSULE,

    /** The Inbox and the session screen: the full request, its findings and the session it belongs to. */
    FULL,
}

/** How an approval may be given on a surface. Denying is always one tap, everywhere. */
enum class ApproveGesture {
    /** One tap approves. */
    ONE_TAP,

    /** Approving needs a second, explicit step that states the consequence. */
    CONFIRM,

    /** This surface cannot approve; it opens the Inbox, where the whole request is shown. */
    OPEN_INBOX,
}

/**
 * One rule for every surface: the less context a surface shows, the lower the risk it may approve. The shade (no
 * unlock, one line) stops at MEDIUM, the Capsule at HIGH, and the Inbox approves everything but asks once more for
 * CRITICAL, so the most dangerous action is never the easiest to tap.
 */
fun approveGesture(level: RiskLevel, surface: ApprovalSurface): ApproveGesture = when (surface) {
    ApprovalSurface.NOTIFICATION -> if (level <= RiskLevel.MEDIUM) ApproveGesture.ONE_TAP else ApproveGesture.OPEN_INBOX
    ApprovalSurface.CAPSULE -> if (level <= RiskLevel.HIGH) ApproveGesture.ONE_TAP else ApproveGesture.OPEN_INBOX
    ApprovalSurface.FULL -> if (level <= RiskLevel.HIGH) ApproveGesture.ONE_TAP else ApproveGesture.CONFIRM
}

/**
 * The one remembered scope offered next to "Approve once", or null when none may be offered: the narrowest the
 * request allows (an exact command before an app before a whole category), only where the whole request is shown,
 * and never for CRITICAL.
 */
fun offeredRememberScope(request: ApprovalRequest, surface: ApprovalSurface): RememberScope? {
    if (surface != ApprovalSurface.FULL || request.assessment.level >= RiskLevel.CRITICAL) return null
    return NARROWEST_FIRST.firstOrNull { it in request.rememberOptions }
}

private val NARROWEST_FIRST = listOf(
    RememberScope.THIS_COMMAND_THIS_SESSION,
    RememberScope.THIS_APP_THIS_SESSION,
    RememberScope.THIS_CATEGORY_THIS_SESSION,
)
