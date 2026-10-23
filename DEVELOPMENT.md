# Development

The app targets Fire TV running Fire OS 7 / Android 9 (API 28) or newer.
It uses native Android Views, SQLite, Android Keystore and Media3. It has no
Google Play Services dependency and includes no native ABI-specific libraries.

Build with JDK 17, Android SDK 35 and Build Tools 35.0.0:

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
```

Run the D-pad, session, layout and real playback checks on an Android TV 9
emulator or a dedicated test device:

```sh
./gradlew connectedDebugAndroidTest
```

Instrumentation tests use only a local fictional provider and synthetic media.
They clear application data on the test device. They never contact an IPTV account.
Screenshots are saved in `/sdcard/Download/SmartIPTV-TV-testshots`.

Release APKs from CI are unsigned. Sign them with the owner's private keystore
outside this repository using `zipalign` and `apksigner`. Never commit a keystore,
password, provider server, account data or signed session. Preserve the same
signing key and increase `versionCode` for subsequent updates.

Artwork memory cache: 6 MiB, active-screen targets only. Disk thumbnails: 64 MiB.
Scrolling and changing tabs cancel obsolete image requests. Catalog records are
streamed into a transactional SQL cache; the grid retains at most 200 records.

Catalog synchronization uses two network workers and one importer. Responses
stream to disposable disk files (128 MiB maximum per section), never a full
in-memory JSON array. Unused provider fields are skipped. The importer stages
250 rows per transaction and publishes a section atomically, retaining its
previous catalog if the download or parse fails. Sections fail independently.
Cached sections are reused for six hours; manual refresh always downloads again.
Network metadata and database/UI queries have separate worker pools. Obsolete
view queries and poster requests are cancelled on navigation. Poster downloads
stream to disk and the pending thumbnail queue is capped at 48 jobs.
Autoplay is an in-memory preference persisted with SharedPreferences.apply(),
independent of SQLite writer locks. Database version 2 migrates saved progress,
favorites, catalog and the legacy autoplay setting without clearing user data.
The TV emulator runs with 1 GiB RAM; a 27,000-item fixture exercises imports,
autoplay toggles, repeated navigation/details, memory trimming and warm startup.

Database version 3 preserves completed episode history independently from the
latest resume position. Existing completed episodes are migrated to this history.
The episode picker loads watched IDs with one indexed query per series on a
worker; row binding and changing seasons never query SQLite. Completed rows show
a green check and "Assistido" badge. Replaying or removing a resume entry does
not clear the watched badge. Tests cover completion through real playback,
season switching, replay, restart, account isolation, migration and 720p layout.

Publishing is performed manually through Kotomineee's authenticated account,
with a version title and assets only. GitHub Actions does not publish releases.
