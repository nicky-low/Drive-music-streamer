# Drive Streamer — scaffold

A minimal Android app that streams music from a Google Drive folder,
using only the `drive.readonly` scope, with native Android Auto support
via `MediaLibraryService`. No subscription — it's your own app.

## What's already wired up

- **Auth** (`auth/AuthManager.kt`): Google Sign-In requesting only
  `drive.readonly` — read-only, and only as broad as whatever Drive
  content the signed-in account can see. Sign in with your **buffer
  account** (the second Google account that only has your shared music
  folder shared into it), not your main account.
- **Library loading** (`drive/DriveLibraryRepository.kt`): walks your
  music folder one level deep (subfolders = albums), then each album
  folder for audio files.
- **Streaming** (`playback/GoogleDriveDataSource.kt`): a custom ExoPlayer
  `DataSource` that streams bytes directly from
  `files.get?alt=media`, using HTTP Range headers so seeking doesn't
  require re-downloading from the start.
- **Android Auto** (`playback/MusicService.kt`): a `MediaLibraryService`
  — the same official API Google's own reference music app uses. This
  is what makes the app show up properly in Android Auto's UI, not a
  hack layered on top.
- **UI** (`ui/MainActivity.kt`): bare-bones — sign in, paste your music
  folder's Drive ID, load, tap a track to play. It's deliberately
  minimal; you'll want to replace the `ListView` with something nicer
  and add a mini-player, but the plumbing all works.

## Setup steps

### 1. Google Cloud Console
1. Go to console.cloud.google.com, create a new project (or reuse one).
2. **Enable the Google Drive API** (APIs & Services → Library → search
   "Google Drive API" → Enable).
3. **Configure the OAuth consent screen** (APIs & Services → OAuth
   consent screen). Choose **External**, fill in the basic app info.
   Since this is just for personal use, it's fine to leave it in
   "Testing" mode and add your buffer account's email as a test user —
   you don't need to submit for verification.
4. **Create an OAuth client ID** (APIs & Services → Credentials →
   Create Credentials → OAuth client ID → Android):
   - Package name: `com.example.drivestreamer` (or whatever you rename
     it to)
   - SHA-1 fingerprint: get this by running
     `./gradlew signingReport` in Android Studio's terminal once the
     project's open, and copy the debug SHA-1.

### 2. Open in Android Studio
1. Open this folder as an existing project.
2. Let Gradle sync — it'll pull in the dependencies listed in
   `app/build.gradle.kts`.
3. Update `applicationId` in `app/build.gradle.kts` and the
   `namespace`/package if you want something other than
   `com.example.drivestreamer` — just make sure it matches what you
   registered in step 1.

### 3. Get your music folder's Drive ID
1. Sign into your **buffer account** at drive.google.com.
2. Open the shared music folder (or its shortcut).
3. The folder ID is the string after `/folders/` in the URL, e.g.
   `drive.google.com/drive/folders/`**`1AbCdEfGhIjKlMnOpQrStUvWxYz`**
4. You'll paste that ID into the app's text field after signing in.

### 4. Build and run
Run it on a device (not the emulator, if you want to test actual
Android Auto — use the [Android Auto Desktop Head
Unit](https://developer.android.com/training/cars/testing) to test
without a car).

## Known gaps to fill in (this is a scaffold, not a finished app)

- **Token refresh**: `MusicLibraryHolder.currentAccessToken` is set
  once when you load the library. Drive access tokens expire roughly
  hourly — for long listening sessions you'll want `AuthManager` to
  refresh the token proactively and update the holder, rather than
  fetching it once.
- **Error handling**: network failures, expired folder shares, and
  empty folders aren't gracefully handled yet — right now they'll
  mostly just show an empty list.
- **UI polish**: no album art, no now-playing screen, no playback
  progress bar. The `ListView` is functional but not something you'd
  want to live with day to day.
- **Nested subfolders**: the repository only goes one level deep
  (root → album folders → tracks). If your library has deeper nesting
  you'll want to make `loadLibrary` recursive.
- **Offline caching**: right now every play streams fresh from Drive.
  Consider adding a simple disk cache (e.g. via ExoPlayer's
  `CacheDataSource`) so replaying a track doesn't re-fetch it.

## Why this approach

- `drive.readonly` is meaningfully narrower than the "manage your
  entire Drive" scope apps like Symfonium request — read-only, and
  paired with the buffer-account trick, exposure is limited to
  whatever's shared into that account.
- Streaming directly via Range requests means you're not storing your
  whole library locally, which was the constraint that ruled out the
  sync-based approach.
- `MediaLibraryService` is Android's actual sanctioned path for Auto
  integration — you're not fighting the platform, which is why this
  should end up more reliable than Symfonium's shared-folder bug once
  it's finished.
