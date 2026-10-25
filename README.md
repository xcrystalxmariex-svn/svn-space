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
- **Google Drive: working** (sign-in, token refresh, folder listing, uploads). See "Google Drive setup" below.
- OAuth launch points for OneDrive and Dropbox via AppAuth (client IDs still placeholders in `OAuthHelper.kt`)
- Live WebDAV connectivity test on save

### Watched folders
- SAF folder picker with persistable URI permission
- Toggle enable/disable, delete
- Per-folder assignment of connection + remote path + file-type filter (model ready)

### Backup engine
- `ScanAndBackupWorker` walks watched trees, applies filters, uploads via WebDAV or Google Drive, writes verified `BackupRecord`s with SHA-256
- `FreeUpSpaceWorker` only deletes local files that have a verified record (DocumentFile/SAF)

### UI
- MainActivity + bottom nav: Local tree · Cloud tree · Search · Settings
- Expandable tree adapter (local via DocumentFile, cloud via WebDAV or Google Drive listing)
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

## Google Drive setup

Google Cloud project: **svn-space** (project number 606145683240).

- **Google Drive API:** enabled.
- **OAuth client:** the existing *Android* client
  `606145683240-9admvfv6f4vuchepn0fidbhuk95phufn.apps.googleusercontent.com`,
  with **Advanced settings → Enable custom URI scheme** turned on. That lets it accept the
  redirect `com.googleusercontent.apps.606145683240-9admvfv6f4vuchepn0fidbhuk95phufn:/oauth2redirect`,
  which is what AppAuth uses here (public client, PKCE, no client secret).
- **Scopes:** `openid`, `email`, `drive.file`, `drive.appdata`. These are non-sensitive, so the app
  never needs Google's verification review. With `drive.file` the app only sees files and folders it
  created itself, so backups go to a **svn-space** folder in each account's My Drive.
- **Audience:** External, *Testing*. Only the test users listed on the Audience page can sign in.
  In Testing, Google expires each account's login after 7 days; when that happens, connect that
  account again (the app reuses the existing connection for the same email).

How the sign-in works in code:
- `OAuthHelper.launchGoogle()` opens Google with the account chooser and consent screen every time,
  so each Google account you pick becomes its own connection ("Google Drive · you@gmail.com").
- AppAuth's `RedirectUriReceiverActivity` catches the redirect (scheme set by
  `manifestPlaceholders["appAuthRedirectScheme"]` in `app/build.gradle.kts`) and forwards it to
  `OAuthRedirectActivity`, which exchanges the code and stores the refresh token encrypted.
- `GoogleDriveClient` refreshes access tokens and does folder creation, listing and multipart uploads.

If you change the client ID, update `GOOGLE_CLIENT_ID` in `OAuthHelper.kt` **and** all three
`appAuthRedirectScheme` placeholders in `app/build.gradle.kts`.

## What you still must supply

1. **OAuth client IDs** for OneDrive and Dropbox – edit `OAuthHelper.kt` with values from Azure / Dropbox App Console.
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
