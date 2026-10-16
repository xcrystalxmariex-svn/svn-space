package space.svn.svnspace.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import space.svn.svnspace.R
import space.svn.svnspace.util.ThemeManager

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.themeStyleRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
