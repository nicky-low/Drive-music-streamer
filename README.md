# Drive Streamer

An Android app that streams music from a Google Drive folder, with full
Android Auto support. No subscription, no third-party app with access to
your Drive — it's your own app, and it can only ever see one shared folder.

Kotlin · Media3 / ExoPlayer · `MediaLibraryService` for Android Auto ·
min SDK 26, target SDK 34.

## What it does

- **Streams straight from Drive.** Audio is read with HTTP Range requests,
  so seeking works and nothing needs to be downloaded or synced first.
- **Three tabs.** *Library* (browse and search), *Now Playing*, and
  *Settings*. A mini-player strip stays above the tab bar on Library and
  Settings whenever something is queued.
- **Library browsing and search.** Folders and songs, with the playing
  track highlighted. The search icon looks across every folder name and
  every song at once (accent- and case-insensitive: "beyonce" finds
  *Beyoncé*), and Back from a folder returns to the same results.
- **Now Playing.** Large artwork, a live seek bar, shuffle and repeat, and a
  background gradient built from the current album art.
- **Android Auto.** Browse folders and songs from the car's screen, play
  them, and use the shuffle and repeat buttons.
- **Survives the app being closed.** Music keeps playing after you swipe the
  app from recents; the library scan keeps going in the background too.
- **Fast restarts.** The parsed library is cached on the device, so opening
  the app doesn't mean re-scanning Drive.

## How access is kept narrow

This is the reason the app exists rather than using a general music player
with Drive support.

- Your real music lives in your **main** Google account.
- That folder is shared (view-only) into a second, otherwise empty **buffer**
  account.
- The app signs into the **buffer** account, requesting only the
  `drive.readonly` scope.

So even if something went wrong, the app could only ever read what's shared
into the buffer account — never your real Drive. Signing in with your main
account would defeat this, so don't.

## Using the app

1. **Sign in** with the buffer account on the first screen.
2. Open **Settings**, paste your music folder's Drive **link** (or just its
   ID) and tap **Load library**. A progress notification shows while it
   scans, and you can cancel it. With a large collection (thousands of songs)
   the first scan takes a while; it keeps running if you leave the app.
3. Go to **Library** to browse or search, and tap a song to play it. You land
   on **Now Playing**.
4. Later launches show the cached library straight away. Tap **Load
   library** again only when you've added or changed music in Drive.

**Signing out** (Settings) also clears everything the account left on the
device: the cached library, cover art, and cached audio. Signing back in
means pasting the folder link and loading the library again.

### Where the song info comes from

Titles and artists come from each file's embedded tags (ID3 and similar),
read straight from the authenticated Drive stream. Files with no usable tags
fall back to parsing the filename (`01 - Artist - Title.mp3` and similar).

### Queues

- Tapping a song in the **app** queues your whole library in folder order,
  starting at that song, so playback carries on into the next folder.
- Tapping a song in **Android Auto** queues just that song's folder, starting
  at that song, so Next and Previous stay within the album.

## Android Auto

- Browsing and playback use the standard Media3 `MediaLibraryService`, the
  same API Google's own reference music app uses.
- Shuffle and repeat appear as buttons on the Now Playing screen and stay in
  sync with the phone, whichever side you change them on.
- If Android Auto starts the app by itself (after a reboot, say), the library
  is loaded from the on-disk cache, so browsing works without opening the app.
- When a new scan finishes, connected browsers are told to refresh.
- Cover art in the browse list loads lazily, only for rows being drawn.

**Testing a sideloaded debug build:** Android Auto hides apps that didn't come
from the Play Store. In Android Auto's developer settings on your phone,
enable **Unknown sources**. To test without a car, use the
[Android Auto Desktop Head Unit](https://developer.android.com/training/cars/testing)
(this needs `adb` on a computer).

## Setup

### 1. Google Cloud Console

1. Create a project at console.cloud.google.com (or reuse one).
2. **Enable the Google Drive API** (APIs & Services → Library).
3. **Configure the OAuth consent screen** (External). Leaving it in
   *Testing* is fine for personal use — add your **buffer account's** email
   as a test user. No verification needed.
4. **Create an OAuth client ID** (Credentials → Create credentials → OAuth
   client ID → **Android**):
   - Package name: `com.example.drivestreamer` (or whatever you rename it to)
   - SHA-1 fingerprint: see [The debug keystore](#the-debug-keystore) below.

### 2. Share your music into the buffer account

1. Create a second Google account to act as the buffer (nothing else in it).
2. From your main account, share the music folder with the buffer account as
   **Viewer**.
3. Signed into the buffer account at drive.google.com, open the shared folder
   (or add a shortcut to it) and copy its link from the address bar — or the
   ID after `/folders/`.

### 3. Build

**With Android Studio:** open the folder as a project, let Gradle sync, and
run it on a device. If you change `applicationId` or the package, make sure it
matches what you registered in step 1.

**Without Android Studio** — see the next section.

## Building without Android Studio (GitHub Actions)

A GitHub Actions workflow (`.github/workflows/build.yml`) builds a debug APK
on every push to `main` (or `master`), and can be run by hand from the
**Actions** tab. That makes it possible to edit code on a phone and never
install the Android SDK.

1. Push to the repo. The workflow builds in a few minutes.
2. Open the run in the **Actions** tab and download the
   `drivestreamer-debug-apk` artifact (it works from a phone browser or the
   GitHub app).
3. Unzip it to get `app-debug.apk` and install it. You'll need to allow
   *Install unknown apps* for whichever app you downloaded it with.

The workflow uses JDK 17 and Gradle 8.7, and installs only the SDK
platform-tools (Gradle fetches the platforms and build-tools itself).

Android Auto itself can't be tested from a phone alone — it needs a car, or
the Desktop Head Unit on a computer.

### The debug keystore

GitHub's runners are fresh on every build, which would normally give every
APK a different debug signing key — and Google sign-in only works for the
key registered in Cloud Console. So the repo commits a **fixed debug
keystore** (`app/debug.keystore`), and every build, from CI or anywhere else,
has the same SHA-1. Register this once in the Android OAuth client:

```
12:D6:F0:6B:0A:E2:D9:F5:96:90:55:71:9C:3A:5B:A3:31:88:79:5B
```

It's a debug-only key, never used for a signed release, so committing it is
intentional and standard for CI-built debug APKs.

## How it's put together

```
app/src/main/java/com/example/drivestreamer/
├── auth/
│   ├── AuthManager.kt          Google sign-in (drive.readonly), sign-out
│   ├── TokenProvider.kt        Caches the access token; refreshes it before it expires
│   └── SignOutCleanup.kt       Sign-out plus wiping all local data
├── drive/
│   ├── DriveApi.kt             Retrofit interface for the Drive v3 API
│   ├── DriveLibraryRepository.kt   Recursive folder walk and tag reading
│   ├── DriveModels.kt          Response models
│   ├── TrackMetadataLoader.kt  Reads artist/title tags from the Drive stream
│   ├── TrackNameParser.kt      Filename fallback for untagged files
│   ├── LibraryCacheStore.kt    The parsed library, saved as JSON on the device
│   └── AlbumArtLoader.kt       Pulls embedded cover art; in-memory cache
├── playback/
│   ├── MusicService.kt         MediaLibraryService: playback, Android Auto browsing
│   ├── GoogleDriveDataSource.kt    ExoPlayer data source (Range requests, 401 retry)
│   ├── PlaybackCache.kt        1.5 GB LRU audio cache on disk
│   ├── PlaybackClient.kt       One shared MediaController for the whole UI
│   ├── LibraryLoadService.kt   Foreground service for the library scan
│   ├── LibraryLoadState.kt     Scan progress, shared with the UI
│   └── AlbumArtContentProvider.kt  Lazy cover art for Android Auto's browse list
└── ui/
    ├── LoginActivity.kt        Sign-in screen (launcher)
    ├── MainActivity.kt         Hosts the three tabs and the mini-player
    ├── LibraryFragment.kt      Folders, songs, search
    ├── NowPlayingFragment.kt   Player, artwork-based gradient
    ├── SettingsFragment.kt     Account, folder link, Load library
    ├── ArtColors.kt            Picks gradient colours from artwork
    └── ArtBitmaps.kt           Shared artwork decoding
```

### Notes on the main pieces

- **Library scan.** The scan walks the whole folder tree (any depth, using
  cheap metadata-only calls), treats each folder that directly contains audio
  as an album, then reads tags for every track, six at a time. It runs in a
  foreground service so it survives backgrounding and a locked screen, and
  saves the result to `library_cache.json` in the app's internal storage.
- **One library, shared.** `MusicLibraryHolder` keeps the library in memory
  and is the single source for both the Library tab and Android Auto, so they
  can't disagree. It loads from the cache on demand, which is what makes
  Auto work after a cold start.
- **Tokens.** Access tokens are refreshed after 45 minutes (they last about
  an hour), and a `401` mid-stream triggers one retry with a fresh token.
  Every component asks `TokenProvider` for the token, and sign-out invalidates
  them all at once.
- **Cover art.** Pulled out of the audio file itself over the authenticated
  stream, so there's no separate download. It's fetched in the background
  after a track starts, then attached to the track's metadata, which feeds the
  notification, lock screen, Android Auto and the Now Playing tab from one
  fetch. The Now Playing gradient is computed from that same image.
- **Audio cache.** Bytes streamed from Drive are written to a 1.5 GB disk
  cache (least-recently-used eviction), so replaying a track needs no network.
  It lives in the app's cache directory, which Android may clear when storage
  is low — it's a cache of recently played music, not an offline download.
- **Closing the app.** If music is playing when you swipe the app away, it
  carries on. If it's paused, the playback service shuts down too.
- **Notifications.** The media notification opens the app on Now Playing; the
  scan-progress notification opens Settings.

### Dependencies

Media3 (ExoPlayer, session) 1.4.1, Material Components 1.12.0, Retrofit +
OkHttp + Gson for the Drive API, Kotlin coroutines, and Google Play Services
Auth.

## Known limitations

- **One account, one folder.** The app is built around a single buffer
  account and a single music folder.
- **Cached audio only.** Nothing is pinned for offline use. Playback needs a
  connection except for what's already in the audio cache.
- **No playlists or favourites yet.**
- **Search is exact-substring matching.** Every typed word has to appear
  somewhere in a folder name, or in a song's title, artist or folder. There's
  no fuzzy matching for typos, and song results are capped at 200 (the screen
  says so and asks you to narrow the search).
- **Whole library in memory.** Fine for several thousand songs; a library in
  the hundreds of thousands would need a different approach.
- **Phone battery settings.** Some phones (Samsung, Xiaomi, Oppo and others)
  stop background apps aggressively. If playback or a library scan dies when
  the app is closed, set Drive Streamer's battery usage to *Unrestricted* in
  the phone's settings.
- **The art provider is exported.** `AlbumArtContentProvider` has to be
  `exported` so Android Auto can load cover art. Any app on the device can
  therefore request art through it — image bytes keyed by a Drive file ID, no
  tokens or account details. That's normal for media-art providers; a
  signature-level permission would lock it down, at the cost of Auto not being
  able to read it.
- **Debug builds only.** CI builds a debug APK signed with the committed debug
  key. A release build would need its own keystore.

## Why this approach

- `drive.readonly` is far narrower than the "manage all your Drive files"
  scope that general apps ask for, and combined with the buffer account the
  exposure is limited to the one shared folder.
- Streaming with Range requests means your whole library never has to be
  stored on the phone.
- `MediaLibraryService` is Android's supported route into Android Auto, so the
  app behaves like a normal media app there rather than working around the
  platform.