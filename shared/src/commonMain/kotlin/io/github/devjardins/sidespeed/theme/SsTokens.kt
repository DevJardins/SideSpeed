package io.github.devjardins.sidespeed.theme

/**
 * Calm dark-room design tokens (TV design Revision 4), shared by the TV app and the companion.
 * Colours are ARGB as Long so this has no Compose dependency. No bright fills, no lime, never pure white.
 */
object SsTokens {
    const val BACKGROUND = 0xFF10151E
    const val SURFACE = 0xFF171E29
    const val BUTTON = 0xFF1A222E
    const val RAISED = 0xFF222C38
    const val FOCUS_BUTTON = 0xFF2A3644
    const val TEXT_PRIMARY = 0xFFEEF1F5
    const val TEXT_BODY = 0xFFD9DEE6
    const val TEXT_SECONDARY = 0xFF8C96A4
    const val ACCENT = 0xFF93BFA2
    const val OK_PILL_BG = 0xFF1D2B24
    const val OK_PILL_TEXT = 0xFF9FC9AC
    const val WARN_PILL_BG = 0xFF2A2620
    const val WARN_PILL_TEXT = 0xFFC9B48F
    const val DANGER_CARD_BG = 0xFF2E1F22
    const val DANGER_CARD_TEXT = 0xFFE3B1B1

    /** TV card reach accents: calm, at ACCENT's lightness, for dark rooms. */
    const val GLOW_LAN = ACCENT
    const val GLOW_TAILNET = 0xFF8FAACC
    const val GLOW_UNREACHABLE = 0xFFC98A8A
    /** Muted coral orange for a card waiting to be trusted. */
    const val GLOW_TRUST = 0xFFD97757

    /** Type sizes in sp. */
    const val HEADLINE_SP = 28
    const val TITLE_SP = 20
    const val BODY_SP = 16
    const val CAPTION_SP = 13
}
