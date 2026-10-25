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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.widget.Toast
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
            val ctx = requireContext().applicationContext
            binding.btnScanNow.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val start = try {
                    withContext(Dispatchers.IO) { BackupScheduler.scanNow(ctx) }
                } catch (e: Exception) {
                    _binding?.txtLastScan?.text = "Scan couldn't start: ${e.message ?: e.javaClass.simpleName}"
                    null
                }
                _binding?.btnScanNow?.isEnabled = true
                when (start) {
                    BackupScheduler.ScanStart.ALREADY_RUNNING ->
                        Toast.makeText(ctx, "A scan is already running", Toast.LENGTH_SHORT).show()
                    BackupScheduler.ScanStart.RESTARTED ->
                        Toast.makeText(ctx, "The last scan had stalled, so it was restarted", Toast.LENGTH_LONG).show()
                    else -> {}
                }
                render()
            }
        }
        // Follow the real state of the scan. Every scan (Scan Now, scheduled, or started by
        // new files) carries the scan tag. The label is only ever drawn from this state, never
        // set ahead of it, so it can't get stuck on a word nobody replaces.
        WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData(BackupScheduler.SCAN_TAG)
            .observe(viewLifecycleOwner) { infos ->
                scanInfos = infos.orEmpty()
                render()
            }
        binding.btnFreeUp.setOnClickListener {
            space.svn.svnspace.ui.common.FreeUpSpaceDialog.show(requireContext(), viewLifecycleOwner)
        }

        refreshStorage()
        lifecycleScope.launch {
            repo.observeConnections().collectLatest { list ->
                val enabled = list.filter { it.isEnabled }
                adapter.submit(enabled)
                binding.txtNoProviders.visibility = if (enabled.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        // Live: Room re-runs the count every time the scan saves a record, so this goes up
        // file by file during a scan and matches the "uploaded" number.
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeCountVerified().collectLatest { count ->
                _binding?.txtBackedUp?.text = if (count > 0) "$count files" else "—"
            }
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
        render()
    }

    private var scanInfos: List<WorkInfo> = emptyList()

    /** Re-checks every 10 s, so "waiting" and "stuck" show up even when nothing changes. */
    private val ticker = object : Runnable {
        override fun run() {
            render()
            _binding?.root?.postDelayed(this, 10_000)
        }
    }

    override fun onStart() {
        super.onStart()
        _binding?.root?.removeCallbacks(ticker)
        _binding?.root?.postDelayed(ticker, 10_000)
    }

    override fun onStop() {
        _binding?.root?.removeCallbacks(ticker)
        super.onStop()
    }

    private fun render() {
        val infos = scanInfos
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val waitingManual = infos.firstOrNull {
            (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) &&
                it.periodicityInfo == null && it.constraints.contentUriTriggers.isEmpty()
        }
        showScanState(running ?: waitingManual)
    }

    private fun showScanState(info: WorkInfo?) {
        val b = _binding ?: return
        val now = System.currentTimeMillis()
        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val prefs = requireContext().getSharedPreferences(ScanAndBackupWorker.PREFS, Context.MODE_PRIVATE)
                val asked = prefs.getLong(BackupScheduler.KEY_SCAN_REQUESTED_AT, 0L)
                b.txtLastScan.text = if (asked > 0 && now - asked > 90_000) {
                    "Scan: Android hasn't started the scan yet. Battery saver or background limits " +
                        "can hold it back. Tap Scan Now to try again."
                } else {
                    "Scan: waiting for Android to start it…"
                }
            }
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                val beat = p.getLong(ScanAndBackupWorker.KEY_HEARTBEAT, 0L)
                val phase = p.getString(ScanAndBackupWorker.KEY_PHASE)
                val current = p.getString(ScanAndBackupWorker.KEY_CURRENT)
                val uploaded = p.getInt(ScanAndBackupWorker.KEY_UPLOADED, 0)
                val checked = p.getInt(ScanAndBackupWorker.KEY_CHECKED, 0)
                if (beat > 0 && now - beat > ScanAndBackupWorker.STALE_MS) {
                    val mins = ((now - beat) / 60_000).coerceAtLeast(1)
                    b.txtLastScan.text = "Scan: no sign of life for $mins min – it looks stuck. Tap Scan Now to restart it."
                    return
                }
                b.txtLastScan.text = buildString {
                    when {
                        phase == null || phase == "Starting" && current == null -> append("Scan: starting")
                        phase == "Scanning" -> append("Scanning" + (current?.let { " $it" } ?: ""))
                        else -> append(phase)
                    }
                    append("…")
                    val parts = mutableListOf<String>()
                    if (checked > 0) parts += "$checked checked"
                    if (uploaded > 0) parts += "$uploaded uploaded"
                    if (parts.isNotEmpty()) append(" (" + parts.joinToString(", ") + ")")
                }
            }
            else -> showLastScan()
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
