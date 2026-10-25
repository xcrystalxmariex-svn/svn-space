package space.svn.svnspace.ui.common

import android.content.Context
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.svn.svnspace.worker.FreeUpSpacePlan
import space.svn.svnspace.worker.FreeUpSpaceWorker

/**
 * Free Up Space confirmation, shared by the dashboard card and the menu item.
 * It works out how much would be freed before asking, so the dialog shows a real
 * size and file count, or says plainly that there's nothing to free.
 */
object FreeUpSpaceDialog {

    fun show(context: Context, owner: LifecycleOwner) {
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad / 2)
            addView(ProgressBar(context))
            addView(TextView(context).apply {
                text = "Checking which files are backed up…"
                setPadding(pad / 2, 0, 0, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        var job: Job? = null
        val progress = AlertDialog.Builder(context)
            .setTitle("Free up space")
            .setView(row)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .setOnCancelListener { job?.cancel() }
            .show()

        job = owner.lifecycleScope.launch {
            val plan = try {
                withContext(Dispatchers.IO) { FreeUpSpacePlan.build(context.applicationContext) }
            } catch (e: Exception) {
                progress.dismiss()
                AlertDialog.Builder(context)
                    .setTitle("Free up space")
                    .setMessage("Couldn't work out what can be freed: ${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            progress.dismiss()
            confirm(context, owner, plan)
        }
    }

    private fun confirm(context: Context, owner: LifecycleOwner, plan: FreeUpSpacePlan) {
        val accessNote = if (plan.skippedNoAccess > 0) {
            "\n\n${plan.skippedNoAccess} backed-up " + files(plan.skippedNoAccess) +
                " in Downloads or similar folders can't be checked until you allow All files access, so " +
                (if (plan.skippedNoAccess == 1) "it isn't" else "they aren't") + " counted."
        } else ""

        if (plan.fileCount == 0) {
            AlertDialog.Builder(context)
                .setTitle("Nothing to free up")
                .setMessage(
                    "No files on this phone have a verified backup yet, so there's nothing to delete. " +
                        "Run Scan Now to back up your watched folders first." + accessNote
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val size = Formatter.formatFileSize(context, plan.totalBytes)
        AlertDialog.Builder(context)
            .setTitle("Free up $size?")
            .setMessage(
                "This deletes ${plan.fileCount} " + files(plan.fileCount) + " ($size) from this phone. " +
                    "Only files with a verified backup are deleted, and the cloud copies stay. " +
                    "This can't be undone." + accessNote
            )
            .setPositiveButton("Free up $size") { _, _ -> start(context, owner) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun start(context: Context, owner: LifecycleOwner) {
        val app = context.applicationContext
        val id = FreeUpSpaceWorker.enqueue(app)
        WorkManager.getInstance(app).getWorkInfoByIdLiveData(id).observe(owner) { info ->
            if (info == null || !info.state.isFinished) return@observe
            val msg = if (info.state == WorkInfo.State.SUCCEEDED) {
                val bytes = info.outputData.getLong(FreeUpSpaceWorker.KEY_FREED_BYTES, 0)
                val count = info.outputData.getInt(FreeUpSpaceWorker.KEY_FREED_COUNT, 0)
                val failed = info.outputData.getInt(FreeUpSpaceWorker.KEY_FAILED, 0)
                "Freed ${Formatter.formatFileSize(app, bytes)} ($count " + files(count) + ")" +
                    if (failed > 0) ". $failed " + files(failed) + " couldn't be deleted." else "."
            } else "Free up space didn't finish."
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun files(n: Int) = if (n == 1) "file" else "files"
}
