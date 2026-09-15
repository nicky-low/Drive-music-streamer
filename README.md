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
- **Token refresh** (`auth/TokenProvider.kt`): caches the access token
  and proactively refreshes it after 45 minutes (Drive tokens are
  typically valid ~1 hour), so long listening sessions don't hit a
  dead token mid-track. If a request still comes back `401` (edge
  case — token revoked, clock skew, etc.), `GoogleDriveDataSource`
  invalidates it and retries once with a forced-fresh token before
  giving up. `MusicService` owns its *own* `TokenProvider` and
  re-establishes the signed-in account on `onCreate()` — this matters
  because Android Auto can start the service directly (e.g. after a
  reboot) without `MainActivity` having run first, and Play Services
  persists sign-in state on-device so this works without user
  interaction.
- **Album art** (`drive/AlbumArtLoader.kt`): pulls embedded cover art
  (ID3 APIC frames, FLAC PICTURE blocks, etc.) directly out of each
  audio file via `MediaMetadataRetriever`, which supports HTTP(S)
  sources with custom headers — so it points straight at the
  authenticated Drive stream, no separate download step. `MusicService`
  fetches it in the background right after a track starts playing (so
  playback isn't blocked waiting on it) and attaches it to that
  track's session metadata, which is what feeds the lock screen,
  notification, Android Auto's now-playing display, and our own
  now-playing screen — all from one place, not four separate fetches.
  Results are cached in memory per file ID so replaying a track is
  instant.
- **Album art in the Android Auto browse list**
  (`playback/AlbumArtContentProvider.kt`): rather than fetching art for
  every track before the browse list can even render, each `MediaItem`
  carries an `artworkUri` pointing at a small `ContentProvider`. Auto
  (or our own app) resolves that URI — and so triggers the actual
  fetch — only when it's about to draw that row, so cost scales with
  what's on screen rather than your whole library. It shares the same
  `AlbumArtLoader` cache as playback, so art fetched for a browse row
  is already warm if you then play that track, and vice versa.
  **Trade-off worth knowing**: the provider is `android:exported="true"`,
  which means any app on the device can technically request art through
  it (just image bytes, keyed by a Drive file ID — no auth tokens or
  account info are exposed). That's normal for media-art providers, but
  if it bothers you, restrict it with a signature-level permission.
  `playback/PlaybackClient.kt`): shows the current track/album, a
  live seek bar, and play/pause/skip. `PlaybackClient` is a small
  singleton holding one shared `MediaController` so switching between
  the library screen and now-playing doesn't tear down and rebuild
  the connection to `MusicService` — which matters because doing that
  naively can cause a playback blip each time you navigate.
- **Offline caching** (`playback/PlaybackCache.kt`): wraps the Drive
  data source in ExoPlayer's `CacheDataSource`, backed by a disk cache
  capped at 1.5GB (`LeastRecentlyUsedCacheEvictor` — oldest-played
  bytes get evicted first once you hit the cap, no manual cleanup
  needed). Bytes fetched from Drive are written to disk as they
  stream past, so replaying a track — or reaching a point you've
  already buffered past once — is instant and needs no network. It
  lives in `context.cacheDir`, which Android is allowed to clear
  under storage pressure — that's intentional: this is a *cache* of
  recently played material, not a permanent offline download of your
  library, which is the behavior that fits your storage constraint.
- **UI** (`ui/MainActivity.kt`): bare-bones — sign in, paste your music
  folder's Drive ID, load, tap a track to open the now-playing screen
  and start it. Still no album browsing UI (just a flat track list);
  worth replacing with a `RecyclerView` once you're happy with the
  plumbing.

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

## Building from a phone (Acode + GitHub, no Android Studio needed)

If you're editing in Acode and syncing via GitHub rather than running
Android Studio day to day, a committed GitHub Actions workflow
(`.github/workflows/build.yml`) builds a debug APK in the cloud on
every push, so you never need a local Android SDK to iterate.

1. Push this project to a GitHub repo (public or private both work —
   GitHub's free tier includes CI minutes for both).
2. Any push to `main` triggers the build automatically. You can also
   trigger it manually from the repo's **Actions** tab → select the
   workflow → **Run workflow**.
3. Once it finishes (a few minutes), open that run in the **Actions**
   tab and scroll to **Artifacts** — download `drivestreamer-debug-apk`.
   This works fine from your phone's browser or the GitHub app.
4. Unzip it (most file managers handle this, or the "Files" app can)
   to get `app-debug.apk`, then tap it to install. You'll need to
   allow "install unknown apps" for whichever app you downloaded it
   through (Settings → Apps → [Browser/Files/GitHub] → Install unknown
   apps).

### The fixed debug keystore, and why it matters here

Normally Android Studio auto-generates a debug signing key per
machine, but since GitHub's runners are fresh every build, this repo
ships a **fixed, committed debug keystore** (`app/debug.keystore`) so
every build — from CI or any machine — produces the exact same SHA-1
fingerprint. That's what lets you register it with Google Cloud
Console once and have it keep working, rather than re-registering
every time you build somewhere new.

This keystore's SHA-1 fingerprint (paste this into the Android OAuth
client ID form in Cloud Console — **step 6/7** in the setup guide
above; you can skip running `signingReport` yourself since this value
is already fixed):

```
12:D6:F0:6B:0A:E2:D9:F5:96:90:55:71:9C:3A:5B:A3:31:88:79:5B
```

It's a debug-only key (never used for a real signed release), so
there's no security concern in it being committed to the repo —
that's intentional and standard practice for CI-built debug APKs.

### What this does and doesn't cover

- **Does**: lets you edit code in Acode, push, and get an installable
  APK back without ever touching Android Studio.
- **Doesn't**: let you test Android Auto integration from your phone
  alone — Auto testing needs either a real car, or a laptop running
  the [Android Auto Desktop Head
  Unit](https://developer.android.com/training/cars/testing), which
  in turn needs `adb` (Android SDK platform-tools) installed — a much
  lighter install than full Android Studio, but still a one-time
  laptop step you can't avoid for that specific piece.



## Known gaps to fill in (this is a scaffold, not a finished app)

- **Error handling**: network failures, expired folder shares, and
  empty folders aren't gracefully handled yet — right now they'll
  mostly just show an empty list.
- **UI polish**: no playback progress bar styling beyond a basic
  `SeekBar`, no album browsing UI (just a flat track list). The
  `ListView` is functional but not something you'd want to live with
  day to day.
- **Nested subfolders**: the repository only goes one level deep
  (root → album folders → tracks). If your library has deeper nesting
  you'll want to make `loadLibrary` recursive.
- **True offline pinning**: the disk cache (see above) only holds
  what you've *already streamed*, and Android can clear it under
  storage pressure. If you want to explicitly mark specific
  albums/tracks as "always available offline" — closer to what
  Spotify's download button does — that needs a second, separate
  mechanism: a `CacheWriter` pass that proactively downloads chosen
  tracks into the cache (or a dedicated non-evictable directory) ahead
  of time, rather than relying on the LRU cache filling in
  opportunistically as you listen.
- **Background token refresh for Auto's "resume" case**: if the OS
  kills the app process entirely and Android Auto later asks it to
  resume/restore playback, `MusicService.onCreate()` re-fetches the
  account and gets a token on first use — that first request will
  block briefly on the network. Fine for a personal app; if it
  bothers you, prefetch a token in `onCreate()` before any playback
  request comes in.

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
