package com.voice2text.android.ui

import android.app.Activity
import android.graphics.Color

/**
 * Defines preset color themes and applies them to Activities.
 *
 * Each theme specifies a background, primary text, and secondary text color.
 * Call [applyTo] in `onCreate` after `setContentView` to tint the window.
 */
data class ThemeColors(
    val background: Int,
    val text: Int,
    val secondaryText: Int
) {
    companion object {
        private val LIGHT = ThemeColors(
            background = Color.parseColor("#FFFFFF"),
            text = Color.parseColor("#212121"),
            secondaryText = Color.parseColor("#757575")
        )
        private val DARK = ThemeColors(
            background = Color.parseColor("#303030"),
            text = Color.parseColor("#EEEEEE"),
            secondaryText = Color.parseColor("#BDBDBD")
        )
        private val AMOLED = ThemeColors(
            background = Color.parseColor("#000000"),
            text = Color.parseColor("#FFFFFF"),
            secondaryText = Color.parseColor("#B0B0B0")
        )
        private val SEPIA = ThemeColors(
            background = Color.parseColor("#F5E6CA"),
            text = Color.parseColor("#5B4636"),
            secondaryText = Color.parseColor("#8B7355")
        )

        /** All available theme keys in display order. */
        val KEYS = listOf("light", "dark", "amoled", "sepia")

        /** Returns the [ThemeColors] for a given preference key. */
        fun forKey(key: String): ThemeColors = when (key) {
            "dark" -> DARK
            "amoled" -> AMOLED
            "sepia" -> SEPIA
            else -> LIGHT
        }
    }

    /** Applies this theme's background color to the Activity's window and decor view. */
    fun applyTo(activity: Activity) {
        activity.window.decorView.setBackgroundColor(background)
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
    }
}
