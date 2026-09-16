package com.dugcanlift.kit

/**
 * DUGCANLIFT palette as ARGB Longs -- the same values as LiftCore.Theme on iOS
 * and the site's CSS. LiftCore's Theme.swift is the canonical source; change a
 * value there first.
 *
 * The dark set is the original palette. The light set was designed on
 * 2026-09-16 to the same warm brown-and-rust brand, and every text pairing
 * passes WCAG AA. Rust and sage darken in light: at their dark values they fall
 * to 3.4:1 and 4.4:1 on a pale ground.
 */
object DclPalette {
    const val BG = 0xFF1C1B19L; const val SURFACE = 0xFF242220L; const val TEXT = 0xFFEDE7DDL; const val MUTED = 0xFFA39C8EL
    const val ACCENT = 0xFFC1442CL; const val ACCENT2 = 0xFF7C8B7AL; const val RULE = 0xFF3A3733L; const val ON_ACCENT = 0xFFF7F1E8L

    const val BG_LIGHT = 0xFFF4EFE7L; const val SURFACE_LIGHT = 0xFFFFFCF7L; const val TEXT_LIGHT = 0xFF26221EL
    const val MUTED_LIGHT = 0xFF665E52L; const val ACCENT_LIGHT = 0xFFB23C25L; const val ACCENT2_LIGHT = 0xFF56664FL
    const val RULE_LIGHT = 0xFFDCD3C5L; const val ON_ACCENT_LIGHT = 0xFFFFFAF3L
}
