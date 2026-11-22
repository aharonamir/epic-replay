package app.epicreplay.core.compose

/** How a graphics skin sets the type of a headline or the replay bug (the `.css` files in the PC's `graphics/skins`): the heavy italic face or the bold upright one, and the letter spacing in em. All three set capitals. */
class OverlaySkin(val heavyItalic: Boolean, val letterSpacingEm: Float) {
    companion object {
        private val SKINS = mapOf("stadium" to OverlaySkin(true, 0.01f), "bulletin" to OverlaySkin(false, 0.06f), "field_notes" to OverlaySkin(false, 0.14f))

        /** The skin named in a pack's `graphics.skin`; one the phone does not know is drawn as the plain bold one (the PC would not render it and fall back to FFmpeg text). */
        fun of(name: String): OverlaySkin = SKINS[name] ?: OverlaySkin(false, 0.02f)
    }
}
