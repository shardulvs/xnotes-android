package com.xnotes.core.model

/**
 * The audio pill's colours, all drawn from the app's accent so a recording reads as something to
 * tap rather than as ink. The chrome sets [current] from its theme, and the pill follows the theme's
 * paper; it is one look for every pill in the process.
 */
data class AudioLook(
    val body: Rgba,
    val disc: Rgba,
    val glyph: Rgba,
    val name: Rgba,
    val time: Rgba,
    /** The time while the player is open on this pill. */
    val timeActive: Rgba,
    val bar: Rgba,
    val barPlayed: Rgba,
    /** The flat bars of a waveform not read yet. */
    val barPending: Rgba,
) {
    companion object {
        /** Built from a Material scheme's primary roles over the paper the pill usually sits on. */
        fun of(primary: Rgba, onPrimary: Rgba, primaryContainer: Rgba, onPrimaryContainer: Rgba, paper: Rgba): AudioLook {
            val body = mix(primaryContainer, paper, 0.5)
            return AudioLook(
                body = body,
                disc = primary,
                glyph = onPrimary,
                name = onPrimaryContainer,
                time = mix(onPrimaryContainer, body, 0.25),
                timeActive = primary,
                bar = mix(primary, body, 0.6),
                barPlayed = primary,
                barPending = mix(primary, body, 0.8),
            )
        }

        /** The app's default dark scheme, for anything drawn before the chrome sets its own. */
        val DEFAULT = of(
            primary = Rgba(236, 192, 108),
            onPrimary = Rgba(65, 45, 0),
            primaryContainer = Rgba(93, 66, 1),
            onPrimaryContainer = Rgba(255, 222, 166),
            paper = Rgba(26, 27, 33),
        )

        @Volatile
        var current: AudioLook = DEFAULT

        private fun mix(a: Rgba, b: Rgba, t: Double): Rgba = Rgba(
            (a.r + (b.r - a.r) * t).toInt().coerceIn(0, 255),
            (a.g + (b.g - a.g) * t).toInt().coerceIn(0, 255),
            (a.b + (b.b - a.b) * t).toInt().coerceIn(0, 255),
        )
    }
}
