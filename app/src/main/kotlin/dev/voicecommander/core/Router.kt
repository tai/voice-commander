package dev.voicecommander.core

/** Where the committed text goes. Decided at Send time, displayed in the popup. */
enum class Route { TERMUX, FOCUSED_FIELD, CLIPBOARD }

object Router {
    val TERMUX_PACKAGE = "com.termux"

    /** System surfaces that must never be treated as the delivery target. */
    private fun isSystemChrome(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg.contains("inputmethod")

    /**
     * The delivery target: trust the LIVE active-window package over the
     * cached one (window-state events get overwritten by systemui/IME and can
     * go stale), and never deliver to system chrome or our own window.
     * The caller supplies the screenshots; the decision stays pure.
     */
    fun effectiveFrontmost(cached: String?, live: String?, ownPackage: String): String? {
        fun usable(pkg: String?) = pkg?.takeIf { it != ownPackage && !isSystemChrome(it) }
        return usable(live) ?: usable(cached)
    }

    fun routeFor(
        frontmostPackage: String?,
        hasEditableNode: Boolean,
        accessibilityActive: Boolean,
        ownPackage: String,
    ): Route = when {
        !accessibilityActive -> Route.CLIPBOARD
        frontmostPackage == TERMUX_PACKAGE -> Route.TERMUX
        frontmostPackage != null && frontmostPackage != ownPackage && hasEditableNode -> Route.FOCUSED_FIELD
        else -> Route.CLIPBOARD
    }
}