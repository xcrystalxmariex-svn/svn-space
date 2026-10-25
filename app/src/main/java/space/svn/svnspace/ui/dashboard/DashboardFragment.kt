package space.svn.svnspace.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import space.svn.svnspace.data.model.Connection
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.databinding.FragmentDashboardBinding
import space.svn.svnspace.databinding.ItemProviderRowBinding
import space.svn.svnspace.ui.connections.AddConnectionActivity
import space.svn.svnspace.ui.connections.ProvidersActivity
import space.svn.svnspace.ui.settings.SettingsActivity
import space.svn.svnspace.worker.BackupScheduler
import space.svn.svnspace.worker.FreeUpSpaceWorker
import space.svn.svnspace.worker.ScanAndBackupWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.content.Context
import android.text.format.DateUtils
import java.util.Locale

/**
 * Home dashboard matching product mockups: status, storage, providers, actions.
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: SvnRepository
    private lateinit var adapter: ProviderAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = SvnRepository(requireContext())
        adapter = ProviderAdapter()
        binding.recyclerProviders.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerProviders.adapter = adapter

        binding.btnSettingsQuick.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.txtSeeAll.setOnClickListener {
            startActivity(Intent(requireContext(), ProvidersActivity::class.java))
        }
        binding.btnAddProvider.setOnClickListener {
            startActivity(Intent(requireContext(), AddConnectionActivity::class.java))
        }
        binding.btnScanNow.setOnClickListener {
            BackupScheduler.scanNow(requireContext())
            binding.txtLastScan.text = "Scan: starting…"
        }
        // Follow the real state of the scan instead of a fixed "queued" label.
        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(BackupScheduler.SCAN_NOW_WORK)
            .observe(viewLifecycleOwner) { infos -> showScanState(infos?.lastOrNull()) }
        binding.btnFreeUp.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(space.svn.svnspace.R.string.free_up_space_confirm_title)
                .setMessage(space.svn.svnspace.R.string.free_up_space_confirm_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    FreeUpSpaceWorker.enqueue(requireContext())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        refreshStorage()
        lifecycleScope.launch {
            repo.observeConnections().collectLatest { list ->
                val enabled = list.filter { it.isEnabled }
                adapter.submit(enabled)
                binding.txtNoProviders.visibility = if (enabled.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            val count = repo.countVerified()
            binding.txtBackedUp.text = if (count > 0) "$count files" else "—"
        }
    }

    private fun refreshStorage() {
        try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free
            binding.txtDeviceUsed.text = formatBytes(used)
        } catch (_: Exception) {
            binding.txtDeviceUsed.text = "—"
        }
        showLastScan()
    }

    private fun showScanState(info: WorkInfo?) {
        val b = _binding ?: return
        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                b.txtLastScan.text = "Scan: waiting to start…"
            WorkInfo.State.RUNNING -> {
                val current = info.progress.getString(ScanAndBackupWorker.KEY_CURRENT)
                val uploaded = info.progress.getInt(ScanAndBackupWorker.KEY_UPLOADED, 0)
                b.txtLastScan.text = buildString {
                    append("Scanning")
                    if (current != null) append(" $current")
                    append("…")
                    if (uploaded > 0) append(" ($uploaded uploaded)")
                }
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                showLastScan()
                lifecycleScope.launch {
                    val count = repo.countVerified()
                    _binding?.txtBackedUp?.text = if (count > 0) "$count files" else "—"
                }
            }
            null -> showLastScan()
        }
    }

    private fun showLastScan() {
        val b = _binding ?: return
        val prefs = requireContext().getSharedPreferences(ScanAndBackupWorker.PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(ScanAndBackupWorker.KEY_LAST_AT, 0L)
        val summary = prefs.getString(ScanAndBackupWorker.KEY_LAST_SUMMARY, null)
        b.txtLastScan.text = if (at == 0L) {
            "Last scan: —"
        } else {
            val whenText = DateUtils.getRelativeTimeSpanString(
                at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            "Last scan: $whenText" + (summary?.let { "\n$it" } ?: "")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ProviderAdapter : RecyclerView.Adapter<ProviderAdapter.VH>() {
        private val items = mutableListOf<Connection>()

        fun submit(list: List<Connection>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemProviderRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        class VH(private val b: ItemProviderRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(c: Connection) {
                b.txtProviderName.text = c.displayName
                b.txtProviderMeta.text = c.providerHint ?: c.type.name
                b.txtProviderStatus.text = if (c.isEnabled) "Connected" else "Off"
                b.txtProviderIcon.text = when {
                    c.type.name.contains("GOOGLE") -> "G"
                    c.type.name.contains("MICROSOFT") -> "M"
                    c.type.name.contains("DROPBOX") -> "D"
                    else -> "☁"
                }
            }
        }
    }
}
