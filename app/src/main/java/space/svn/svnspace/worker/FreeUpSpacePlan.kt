package space.svn.svnspace.worker

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import space.svn.svnspace.data.model.BackupRecord
import space.svn.svnspace.data.repository.SvnRepository
import space.svn.svnspace.util.StorageAccess

/**
 * What Free Up Space would delete right now: the local copies that still exist on the phone
 * and have a verified backup. The confirmation dialog shows its totals, and the worker
 * deletes exactly this list, so the number the user agreed to is the number that gets freed.
 */
class FreeUpSpacePlan private constructor(
    /** One entry per local file, with every verified record that points at it. */
    val candidates: List<Candidate>,
    /** Records whose local file is already gone; the worker cleans these up. */
    val staleRecords: List<BackupRecord>,
    /** Downloads-style files skipped because All files access is off. */
    val skippedNoAccess: Int
) {
    class Candidate(val doc: DocumentFile, val sizeBytes: Long, val records: List<BackupRecord>)

    val fileCount: Int get() = candidates.size
    val totalBytes: Long get() = candidates.sumOf { it.sizeBytes }

    companion object {
        /** Slow on big libraries (one storage lookup per file): call it off the main thread. */
        suspend fun build(context: Context, repo: SvnRepository = SvnRepository(context)): FreeUpSpacePlan {
            val candidates = ArrayList<Candidate>()
            val stale = ArrayList<BackupRecord>()
            var skipped = 0
            val hasAllFiles = StorageAccess.hasAllFilesAccess(context)
            // A file backed up to two accounts has two records; count and delete it once.
            repo.getVerifiedRecords().groupBy { it.localUri }.forEach { (uri, records) ->
                if (StorageAccess.isFileUri(uri) && !hasAllFiles) {
                    // Without All files access a Downloads file just looks missing; leave it.
                    skipped++
                    return@forEach
                }
                val doc = try {
                    StorageAccess.singleDocument(context, uri)
                } catch (e: Exception) {
                    null
                }
                if (doc != null && doc.exists() && doc.isFile) {
                    candidates.add(Candidate(doc, doc.length(), records))
                } else {
                    stale.addAll(records)
                }
            }
            return FreeUpSpacePlan(candidates, stale, skipped)
        }
    }
}
