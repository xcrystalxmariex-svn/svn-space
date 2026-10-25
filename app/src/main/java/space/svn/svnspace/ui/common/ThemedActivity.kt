package space.svn.svnspace.ui.common

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import space.svn.svnspace.util.ThemeManager

/**
 * Base for every screen. Applies the saved theme before any view is created, and
 * recreates the screen when the theme was changed somewhere else while it sat in the
 * back stack (for example the main screen under Settings), so a theme change shows
 * everywhere at once and sticks after a restart.
 */
abstract class ThemedActivity : AppCompatActivity() {
    private var appliedTheme: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyToActivity(this)
        appliedTheme = ThemeManager.currentTheme(this).key
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (appliedTheme != null && appliedTheme != ThemeManager.currentTheme(this).key) {
            recreate()
        }
    }
}
