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
The episode picker loads watched flags and saved resume times with one indexed
query per series on a worker; row binding and changing seasons never query SQLite. Completed rows show
a green check and "Assistido" badge. Replaying or removing a resume entry does
not clear the watched badge. Tests cover completion through real playback,
season switching, replay, restart, account isolation, migration and 720p layout.
Partial episodes show "Parou em mm:ss" (or h:mm:ss) under the episode title.
Exiting episode playback waits for pending progress writes and restores the same
series, current season and focused episode, including after autoplay and playback
started from Continue Watching. The loaded series metadata is reused on return.
Movie exits now restore their details, with cached metadata when available and a
visible saved time. Movie cards show the saved time in place of the year while
partially watched; completion clears that indicator. Live exits return to the catalog.
Favorites use a single recycled grid with full-width Films/Series section headers
and counts. Legacy episode favorites stay in Series, and live favorites retain a
separate TV section. Each section pages independently with the existing metadata
cap; search, ordering and account isolation are applied in SQL before paging.
UI tests cover both exit controls,
resuming with OK, persistent progress, catalog Back navigation and 720p/1080p.

Publishing is performed manually through Kotomineee's authenticated account,
with a version title and assets only. GitHub Actions does not publish releases.

The catalog opens on Favorites for new and remembered sessions. Favorites is the
first sidebar entry; the former Home is labeled Novidades in Favorites' old slot.
Back from other catalog tabs returns to Favorites. Their contents remain unchanged.
Directional navigation uses card rows and section ranges rather than allowing
horizontal focus to wrap into another row. Right at a row edge reaches ordering,
Up above the first card row reaches search, and Down crosses headings into the
next content row. Pending focus survives recycled/offscreen card layout without
database work during a remote event. The native keyboard retains editing keys.
Sidebar, categories, toolbars and Novidades shelves have explicit edge behavior.
