<div align="center">

<img src="assets/zalo-patch-icon.png" width="180" alt="Zalo Patch icon">

# Zalo Patch

</div>

## Features

- Clean up navigation, inbox, chat and Me-screen elements.
- Filter promotional notifications.
- Block analytics and advertising services.
- Local notification history.
- Call audio recording.
- More frequent scheduled message backups while preserving Zalo's native backup guards.

## Requirements

- Rooted device with Android 8.0 (API 26) or newer.
- LSPosed or another framework that supports LibXposed API 102.
- Zalo selected in the module scope.
- Zalo 26.10.01 (`versionCode 261001903` or `261001905`). Earlier supported versions appear in the compatibility catalog.

Saving call recordings requires Android 10 or newer.

For best experience, do not update Zalo.

## Settings

Changes save immediately. Select Restart Zalo to activate them. With root access, this action stops
and reopens Zalo automatically. Without root access, it prepares the saved settings and asks you to
force stop Zalo and open it again. Changes remain pending until Zalo acknowledges them.

Upgrades carry forward the last applied settings without applying newer pending edits. If settings
need preparation, Restart Zalo remains available even when no setting was changed.

## LSPatch

The separate LSPatch artifact targets upstream LSPatch 0.6 build 398, Local mode, on Android 9 or
newer. Install Zalo Patch separately, patch a compatible Zalo APK, and select Zalo Patch for it in
LSPatch Manager. Keep both apps in the same Android user or profile. Select Restart Zalo, then follow
the instructions to force stop and reopen the patched Zalo. The default API 102 artifact does not
load through LSPatch.

Patching changes Zalo's signer. Replacing the official app can require uninstalling it and deleting
its data. Use a disposable environment first and retain the patch signing key for later updates.

## Build

Requires JDK 21, Android SDK platform 37, and the SDK Build Tools selected by Android Gradle Plugin
9.4.1. Use the checked-in Gradle 9.8.1 wrapper. Set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to the SDK.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyFrameworkArtifact
```

This builds and checks the modern debug APK. Add `-Plspatch=true` to build the separate LSPatch
artifact. Run the commands separately for each contract. Checked copies are written under
`app/build/verified/modern/` and `app/build/verified/lspatch/`; ordinary build outputs are shared.
Debug APKs are local test artifacts. Release builds require configured release signing credentials.

## Privacy

- Diagnostic reports are prepared and uploaded only when requested by the user.
- Bounded, sanitized runtime-status metadata is stored locally and may be included in a requested
  diagnostic report.
- Notification history stays in local app storage until exported or cleared.
- Call recordings are shared audio files and survive module uninstall. Their filenames can contain
  contact names and phone numbers.

## License

[MIT](LICENSE)

Zalo Patch is unofficial and not affiliated with or endorsed by Zalo or VNG.

[![Telegram](https://img.shields.io/badge/Telegram-Zalo%20Patch-26A5E4?logo=telegram&logoColor=white)](https://t.me/Zalopatch)
