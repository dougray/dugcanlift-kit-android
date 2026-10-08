package com.dugcanlift.kit

/**
 * DUGCANLIFT palette as ARGB Longs -- the same values as LiftCore.Theme on iOS
 * and the site's CSS. LiftCore's Theme.swift is the canonical source; change a
 * value there first. `DclPaletteTest` checks every constant here against
 * `palette.json`, a byte-for-byte copy of the iOS kit's fixture, so a token
 * added or changed on one side and not the other fails the build.
 *
 * The dark set is the original palette. The light set was designed on
 * 2026-09-16 to the same warm brown-and-rust brand. Rust and sage darken in
 * light: at their dark values they fall to 3.4:1 and 4.4:1 on a pale ground.
 *
 * Every text pairing in `palette.json`'s `textPairs` passes WCAG AA (4.5:1) in
 * both sets. Two rules follow from that:
 * - [ACCENT] is a FILL. As text in dark it is 3.39:1 on [BG] and 3.12:1 on
 *   [SURFACE]. Rust text (headings, values, the active tab) uses [ACCENT_TEXT].
 *   Likewise [ACCENT2] (sage) is a fill or line; sage text uses [ACCENT2_TEXT].
 * - On an [ACCENT] fill, text is [ON_ACCENT] (4.53:1 dark), never [TEXT]
 *   (4.13:1 dark, 2.68:1 light).
 *
 * Intended Material 3 role map, so LIFT and Coach theme identically
 * (dark constants shown; use the `_LIGHT` twin in a light scheme):
 *
 * - primary = ACCENT, onPrimary = ON_ACCENT
 * - secondary = ACCENT2
 * - background = BG; surface and the surfaceContainer family = SURFACE
 * - onBackground, onSurface = TEXT; onSurfaceVariant = MUTED
 * - outline, outlineVariant = RULE
 *
 * Rust and sage text are not Material roles: apply [ACCENT_TEXT] /
 * [ACCENT2_TEXT] directly. Dynamic Color (Material You) is off on purpose:
 * the brand palette is pinned, so do not "fix" a theme by enabling it.
 */
object DclPalette {
    const val BG = 0xFF1C1B19L; const val SURFACE = 0xFF242220L; const val TEXT = 0xFFEDE7DDL; const val MUTED = 0xFFA39C8EL
    const val ACCENT = 0xFFC1442CL; const val ACCENT2 = 0xFF7C8B7AL; const val RULE = 0xFF3A3733L; const val ON_ACCENT = 0xFFF7F1E8L

    const val BG_LIGHT = 0xFFF4EFE7L; const val SURFACE_LIGHT = 0xFFFFFCF7L; const val TEXT_LIGHT = 0xFF26221EL
    const val MUTED_LIGHT = 0xFF665E52L; const val ACCENT_LIGHT = 0xFFB23C25L; const val ACCENT2_LIGHT = 0xFF56664FL
    const val RULE_LIGHT = 0xFFDCD3C5L; const val ON_ACCENT_LIGHT = 0xFFFFFAF3L

    /** Rust as TEXT. Dark is lifted to 5.09:1 on [BG], 4.69:1 on [SURFACE]. Light equals [ACCENT_LIGHT]. */
    const val ACCENT_TEXT = 0xFFE0674DL; const val ACCENT_TEXT_LIGHT = 0xFFB23C25L

    /**
     * Sage as TEXT. [ACCENT2] (0x7C8B7A) is a FILL or LINE: as text in dark it
     * is 4.40:1 on [SURFACE], below AA. Dark is lifted, same hue, to 0x879585:
     * 5.46:1 on [BG], 5.03:1 on [SURFACE]. Light equals [ACCENT2_LIGHT]
     * (5.38:1 on [BG_LIGHT], 6.02:1 on [SURFACE_LIGHT]).
     */
    const val ACCENT2_TEXT = 0xFF879585L; const val ACCENT2_TEXT_LIGHT = 0xFF56664FL

    /** Dimmed accent for pressed and disabled states. Put [TEXT]/[TEXT_LIGHT] on it, not ON_ACCENT. */
    const val ACCENT_MUTED = 0xFF883223L; const val ACCENT_MUTED_LIGHT = 0xFFE7B3A6L

    /**
     * The line round a card. None in dark (fully transparent), where a card
     * already separates from the page by being lighter; a hairline in light,
     * where parchment and near-white are too close in brightness.
     */
    const val CARD_BORDER = 0x00000000L; const val CARD_BORDER_LIGHT = 0xFFDCD3C5L
}
