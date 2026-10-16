# svn-space

Multi-provider folder-watching backup engine with unified cloud + local tree, free-up-space, and prismatic themes.

**Package:** `space.svn.svnspace`  
**Language:** Kotlin  
**minSdk:** 26 · **targetSdk:** 35  

## What is implemented

### Core
- Full Gradle project ready for A-IDE / AndroidIDE / Android Studio
- Room database (Connections, WatchedFolders, BackupRecords)
- EncryptedSharedPreferences for passwords / tokens (SecureStore)
- WorkManager periodic scan + one-shot free-up-space
- BootReceiver to re-schedule work

### Connections
- Credential-based: WebDAV (fully functional client), SFTP/FTP/SMB placeholders, Custom
- OAuth launch points for Google Drive, OneDrive, Dropbox via AppAuth  
  → Put your real client IDs in `OAuthHelper.kt`  
  → Redirect URI: `svnspace://oauth`
- Live WebDAV connectivity test on save

### Watched folders
- SAF folder picker with persistable URI permission
- Toggle enable/disable, delete
- Per-folder assignment of connection + remote path + file-type filter (model ready)

### Backup engine
- `ScanAndBackupWorker` walks watched trees, applies filters, uploads via WebDAV, writes verified `BackupRecord`s with SHA-256
- `FreeUpSpaceWorker` only deletes local files that have a verified record (DocumentFile/SAF)

### UI
- MainActivity + bottom nav: Local tree · Cloud tree · Search · Settings
- Expandable tree adapter (local via DocumentFile, cloud via WebDAV listing)
- Theme engine: System / Dark / Light / Eggplant / Aurora / Sunset / Retrowave / Neon / Cyberpunk / Terminal-Tron / Void / **Alexandrite**
- Adaptive launcher icon (prism / alexandrite mark)

### Settings
- Manage watched folders
- Add connection
- Scan interval
- Theme picker

## How to open & build

1. Copy the entire `svn-space` folder to the device.
2. Open the project root in A-IDE or AndroidIDE.
3. Let Gradle sync.
4. Build → APK.

## What you still must supply

1. **OAuth client IDs** – edit `OAuthHelper.kt` with values from Google Cloud Console, Azure, Dropbox App Console. Register redirect `svnspace://oauth`.
2. **On-device testing** – storage permissions, WebDAV server credentials, actual free-up-space verification.
3. Optional: real SFTP / SMB libraries if you need those protocols beyond the model placeholders.

## Architecture map

```
data/
  db/          Room entities + DAOs + AppDatabase
  model/       Connection, WatchedFolder, BackupRecord
  remote/      WebDavClient (OkHttp)
  repository/  SvnRepository (single entry point)
ui/
  main/        MainActivity
  settings/    Settings + WatchedFolders
  connections/ AddConnection + OAuthHelper + Redirect
  tree/        Local / Cloud / Search + FileTreeAdapter
worker/        ScanAndBackup, FreeUpSpace, Boot, Scheduler
util/          ThemeManager, SecureStore
```

Prism open. No smoothing.
