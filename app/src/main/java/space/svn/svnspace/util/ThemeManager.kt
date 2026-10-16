package space.svn.svnspace.util

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import space.svn.svnspace.R

/**
 * Global theme control. Every Activity must call [applyToActivity] before setContentView.
 * Layouts should use ?attr/colorSurface, ?attr/colorOnSurface, ?attr/colorPrimary, etc.
 * so skins apply app-wide without hardcoded palette colors.
 */
object ThemeManager {

    private const val PREFS = "svn_space_prefs"
    private const val KEY_THEME = "selected_theme"

    enum class ThemeOption(val key: String, val styleRes: Int, val displayNameRes: Int) {
        SYSTEM("system", R.style.Theme_SvnSpace_System, R.string.theme_system),
        DARK("dark", R.style.Theme_SvnSpace_Dark, R.string.theme_dark),
        LIGHT("light", R.style.Theme_SvnSpace_Light, R.string.theme_light),
        EGGPLANT("eggplant", R.style.Theme_SvnSpace_Eggplant, R.string.theme_eggplant),
        AURORA("aurora", R.style.Theme_SvnSpace_Aurora, R.string.theme_aurora),
        SUNSET("sunset", R.style.Theme_SvnSpace_Sunset, R.string.theme_sunset),
        RETROWAVE("retrowave", R.style.Theme_SvnSpace_Retrowave, R.string.theme_retrowave),
        NEON("neon", R.style.Theme_SvnSpace_Neon, R.string.theme_neon),
        CYBERPUNK("cyberpunk", R.style.Theme_SvnSpace_Cyberpunk, R.string.theme_cyberpunk),
        TERMINAL("terminal", R.style.Theme_SvnSpace_Terminal, R.string.theme_terminal),
        VOID("void", R.style.Theme_SvnSpace_Void, R.string.theme_void),
        ALEXANDRITE("alexandrite", R.style.Theme_SvnSpace_Alexandrite, R.string.theme_alexandrite)
    }

    fun applySavedTheme(context: Context) {
        val option = currentTheme(context)
        when (option) {
            ThemeOption.SYSTEM ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            ThemeOption.LIGHT ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else ->
                // Dark + all specialty skins ride the night base; style overlay carries colors
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    /** Call in Activity.onCreate before super.onCreate / setContentView. */
    fun applyToActivity(activity: Activity) {
        applySavedTheme(activity)
        activity.setTheme(themeStyleRes(activity))
    }

    fun setTheme(context: Context, option: ThemeOption) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, option.key)
            .apply()
        applySavedTheme(context)
    }

    fun currentTheme(context: Context): ThemeOption {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, ThemeOption.AURORA.key)
        return ThemeOption.entries.find { it.key == key } ?: ThemeOption.AURORA
    }

    fun themeStyleRes(context: Context): Int = currentTheme(context).styleRes
}
