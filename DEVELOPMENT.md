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
Screenshots are saved under the test app's external files directory.

Release APKs from CI are unsigned. Sign them with the owner's private keystore
outside this repository using `zipalign` and `apksigner`. Never commit a keystore,
password, provider server, account data or signed session. Preserve the same
signing key and increase `versionCode` for subsequent updates.

Artwork memory cache: 6 MiB, active-screen targets only. Disk thumbnails: 64 MiB.
Scrolling and changing tabs cancel obsolete image requests. Catalog records are
streamed into a transactional SQL cache; the grid retains at most 200 records.

Publishing is performed manually through Kotomineee's authenticated account,
with a version title and assets only. GitHub Actions does not publish releases.
