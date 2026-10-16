package space.svn.svnspace.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import space.svn.svnspace.R
import space.svn.svnspace.databinding.ActivityMainBinding
import space.svn.svnspace.ui.dashboard.DashboardFragment
import space.svn.svnspace.ui.settings.SettingsActivity
import space.svn.svnspace.ui.tree.CloudTreeFragment
import space.svn.svnspace.ui.tree.LocalTreeFragment
import space.svn.svnspace.ui.tree.SearchFragment
import space.svn.svnspace.util.ThemeManager
import space.svn.svnspace.worker.FreeUpSpaceWorker

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.themeStyleRes(this))
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    openFragment(DashboardFragment())
                    supportActionBar?.title = getString(R.string.app_name)
                    true
                }
                R.id.nav_files -> {
                    // Files tab: cloud tree (local available from within / settings)
                    openFragment(CloudTreeFragment())
                    supportActionBar?.title = getString(R.string.nav_files)
                    true
                }
                R.id.nav_search -> {
                    openFragment(SearchFragment())
                    supportActionBar?.title = getString(R.string.nav_search)
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    // keep selection on previous tab visually next time
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
    }

    private fun openFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_free_up_space -> {
                confirmFreeUpSpace()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmFreeUpSpace() {
        AlertDialog.Builder(this)
            .setTitle(R.string.free_up_space_confirm_title)
            .setMessage(R.string.free_up_space_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                FreeUpSpaceWorker.enqueue(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
