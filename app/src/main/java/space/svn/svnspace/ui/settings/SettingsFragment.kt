package space.svn.svnspace.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import space.svn.svnspace.R
import space.svn.svnspace.ui.connections.AddConnectionActivity
import space.svn.svnspace.util.ThemeManager
import space.svn.svnspace.worker.BackupScheduler

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Theme picker
        findPreference<ListPreference>("pref_theme")?.apply {
            val entries = ThemeManager.ThemeOption.entries.map { getString(it.displayNameRes) }.toTypedArray()
            val values = ThemeManager.ThemeOption.entries.map { it.key }.toTypedArray()
            entryValues = values
            this.entries = entries
            value = ThemeManager.currentTheme(requireContext()).key

            setOnPreferenceChangeListener { _, newValue ->
                val option = ThemeManager.ThemeOption.entries.find { it.key == newValue }
                if (option != null) {
                    ThemeManager.setTheme(requireContext(), option)
                    // Recreate so every screen picks up the new style overlay
                    requireActivity().recreate()
                }
                true
            }
        }

        // Scan interval
        findPreference<ListPreference>("pref_scan_interval")?.setOnPreferenceChangeListener { _, newValue ->
            val hours = (newValue as String).toLongOrNull() ?: 1L
            BackupScheduler.updateInterval(requireContext(), hours)
            true
        }

        // Add connection shortcut
        findPreference<Preference>("pref_add_connection")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AddConnectionActivity::class.java))
            true
        }

        findPreference<Preference>("pref_manage_watched")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), WatchedFoldersActivity::class.java))
            true
        }
    }
}
