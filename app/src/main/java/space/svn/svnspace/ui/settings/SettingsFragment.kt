package space.svn.svnspace.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.util.SettingsBackup
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
        findPreference<ListPreference>(BackupScheduler.PREF_INTERVAL)?.setOnPreferenceChangeListener { _, newValue ->
            val minutes = (newValue as String).toLongOrNull() ?: 60L
            BackupScheduler.updateInterval(requireContext(), minutes)
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

        findPreference<Preference>("pref_cloud_accounts")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), space.svn.svnspace.ui.connections.ProvidersActivity::class.java))
            true
        }

        findPreference<Preference>("pref_export_settings")?.setOnPreferenceClickListener {
            exportLauncher.launch(SettingsBackup.suggestedFileName())
            true
        }

        findPreference<Preference>("pref_import_settings")?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            true
        }

        // Per-folder advanced settings: connection, remote folder, file types.
        findPreference<Preference>("pref_advanced_info")?.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), WatchedFoldersActivity::class.java)
                    .putExtra(WatchedFoldersActivity.EXTRA_OPEN_ADVANCED, true)
            )
            true
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportTo(uri) }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readForImport(uri) }

    private fun exportTo(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { SettingsBackup.export(ctx, uri) } }
            if (!isAdded) return@launch
            result.onSuccess { what ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Settings exported")
                    .setMessage("Saved $what.\n\nPasswords and Google sign-ins aren't in the file. " +
                        "After importing, you'll reconnect each account.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure { e ->
                Toast.makeText(ctx, "Couldn't export: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readForImport(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { SettingsBackup.read(ctx, uri) } }
            if (!isAdded) return@launch
            result.onSuccess { confirmImport(it) }.onFailure { e ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Can't import this file")
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun confirmImport(p: SettingsBackup.Parsed) {
        val msg = buildString {
            p.exportedAt?.let { append("Exported $it").append(p.appVersion?.let { v -> " (version $v)" } ?: "").append(".\n\n") }
            append("This file has:\n")
            append("\u2022 ${p.connections.size} cloud account(s)")
            if (p.connections.isNotEmpty()) append(": " + p.connections.joinToString { it.displayName })
            append("\n\u2022 ${p.folders.size} watched folder(s)")
            if (p.folders.isNotEmpty()) append(": " + p.folders.joinToString { it.folder.displayPath })
            p.theme?.let { append("\n\u2022 Theme: ${getString(it.displayNameRes)}") }
            p.scanIntervalMinutes?.let { append("\n\u2022 Scan every $it minutes") }
            append("\n\nAccounts and folders are added, or updated if they're already here. Nothing is deleted. ")
            append("Passwords and sign-ins aren't in the file, so accounts without a saved login will ask you to reconnect.")
            if (p.skipped.isNotEmpty()) append("\n\n" + p.skipped.joinToString("\n"))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Import these settings?")
            .setMessage(msg)
            .setPositiveButton("Import") { _, _ -> applyImport(p) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyImport(p: SettingsBackup.Parsed) {
        val ctx = requireContext().applicationContext
        val activity = requireActivity()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { SettingsBackup.apply(ctx, p) } }
            result.onSuccess { r ->
                val msg = buildString {
                    append("Imported ${r.connections} cloud account(s) and ${r.folders} watched folder(s).")
                    if (r.needLogin.isNotEmpty()) {
                        append("\n\nReconnect these in Cloud Accounts (tap the account): " + r.needLogin.joinToString())
                    }
                    if (r.foldersNeedingAccess.isNotEmpty()) {
                        append("\n\nThese folders need access on this phone (open Watch Folders): " +
                            r.foldersNeedingAccess.joinToString())
                    }
                    if (r.notes.isNotEmpty()) append("\n\n" + r.notes.joinToString("\n"))
                }
                AlertDialog.Builder(activity)
                    .setTitle("Settings imported")
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        // Redraw with the imported theme.
                        if (p.theme != null && !activity.isFinishing) activity.recreate()
                    }
                    .show()
            }.onFailure { e ->
                Toast.makeText(ctx, "Import stopped: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
