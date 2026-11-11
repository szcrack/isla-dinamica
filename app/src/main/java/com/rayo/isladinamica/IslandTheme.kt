package com.rayo.isladinamica

import android.content.Context
import android.graphics.Color

/**
 * The three island themes. Each one carries the drawables and colors the
 * [IslandService] overlay needs, so switching themes is just re-applying
 * these values to the already-inflated views.
 */
enum class IslandTheme(
    val label: String,
    val pillBackgroundRes: Int,
    val cardBackgroundRes: Int,
    val titleTextColor: Int,
    val subtitleTextColor: Int,
    val dividerColor: Int,
    val controlBackgroundRes: Int,
    val playPauseBackgroundRes: Int,
    val showProgressBar: Boolean,
    val progressDrawableRes: Int
) {
    BLACK(
        label = "Negro iPhone",
        pillBackgroundRes = R.drawable.pill_background,
        cardBackgroundRes = R.drawable.card_background,
        titleTextColor = Color.WHITE,
        subtitleTextColor = Color.parseColor("#DDDDDD"),
        dividerColor = Color.parseColor("#33FFFFFF"),
        controlBackgroundRes = android.R.color.transparent,
        playPauseBackgroundRes = android.R.color.transparent,
        showProgressBar = true,
        progressDrawableRes = R.drawable.progress_plain
    ),
    FROSTED(
        label = "HyperOS Cristal",
        pillBackgroundRes = R.drawable.pill_frosted_background,
        cardBackgroundRes = R.drawable.card_frosted_background,
        titleTextColor = Color.WHITE,
        subtitleTextColor = Color.parseColor("#DDDDDD"),
        dividerColor = Color.parseColor("#40FFFFFF"),
        controlBackgroundRes = android.R.color.transparent,
        playPauseBackgroundRes = android.R.color.transparent,
        showProgressBar = true,
        progressDrawableRes = R.drawable.progress_plain
    ),
    GLOW(
        label = "HyperOS Brillo",
        pillBackgroundRes = R.drawable.pill_glow_background,
        cardBackgroundRes = R.drawable.card_glow_background,
        titleTextColor = Color.WHITE,
        subtitleTextColor = Color.parseColor("#B9C8FF"),
        dividerColor = Color.parseColor("#33FFFFFF"),
        controlBackgroundRes = R.drawable.circle_dark,
        playPauseBackgroundRes = R.drawable.circle_glow_border,
        showProgressBar = true,
        progressDrawableRes = R.drawable.progress_glow
    );

    companion object {
        const val PREFS_NAME = "island_prefs"
        const val KEY_THEME = "island_theme"

        fun current(context: Context): IslandTheme {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_THEME, BLACK.name)
            return try {
                valueOf(name ?: BLACK.name)
            } catch (e: IllegalArgumentException) {
                BLACK
            }
        }

        fun save(context: Context, theme: IslandTheme) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, theme.name)
                .apply()
        }
    }
}
