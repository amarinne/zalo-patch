<div align="center">

<img src="assets/zalo-patch-icon.png" width="180" alt="Zalo Patch icon">

# Zalo Patch

</div>

## Features

All tweaks default to off. Changes apply after Restart Zalo.

<details>
<summary><b>Show the tweak catalog</b></summary>

| Tweak | What it does |
| :--- | :--- |
| Tab bar | Hides Discovery and Newsfeed tabs; keeps Groups in place. |
| Start in Messages | Always opens directly to Messages on launch. |
| Me rows | Hides QR Wallet, zCloud, zStyle, and zBusiness shortcuts. |
| Media Box & zCloud banner | Removes the Media Box row and collapses the zCloud banner. |
| Inbox chips | Adds conversation list filters (All, Chats, Groups, OA, Strangers). |
| Original-quality photos | Sends picker and shared photos uncompressed at full resolution. |
| Emoji reactions | Adds a full Unicode emoji picker to the reaction bar. |
| Hide reaction row | Removes the reaction bar from the long-press message menu. |
| Recalled messages | Keeps recalled one-to-one text with a recalled label and gray text. Server resync can still remove it. |
| Native Mini Chat | Uses Zalo floating chat heads with keyboard layout fixes. |
| Seen & typing status | Blocks outgoing read receipts and typing indicators. |
| Links | Opens web links in your default browser instead of in-app webview. |
| Story views | Views stories anonymously without appearing on the viewer list. |
| Registered clones | Keeps settings, restart targets, and status separate for registered Zalo packages in the same Android user. |
| Call recording | Records 1-on-1 calls to local audio (`.m4a`). |
| Recording notifications | Shows status alerts when recording starts and saves. |
| Promo filter | Blocks marketing alerts without affecting chat, call, or backup alerts. |
| History | Keeps an opt-in local notification log with retention limits and export. |
| Ad slots | Hides message, feed, story, and video ads. |
| Analytics records | Disables telemetry events, screen tracking, and session analytics. |
| Tracking identifiers | Zeroes Google Advertising ID and blocks Crashlytics logs. |
| Backup interval | Lowers minimum chat backup interval to 1, 3, 6, or 12 hours. |
| App-lock grace period | Extends passcode lock timeout grace period up to 1 hour. |

</details>

## Requirements

- Rooted device with Android 8.0 (API 26) or newer.
- LSPosed or another framework that supports LibXposed API 102.
- Zalo selected in the module scope.
- [Supported Zalo version](https://catalog.eza.dpdns.org/)

Saving call recordings requires Android 10 or newer.

Patched and re-signed builds work when their version code is listed.

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

For a renamed Zalo clone, add its installed package name under Developer tools → Zalo clone
packages, then enable Zalo Patch for that clone in LSPatch Manager. Only add apps you trust:
they can read module settings and submit recordings. Select the installation in Zalo Patch to
manage its settings, restart action, compatibility status, and runtime checks. Install both apps
in the same Android user. Removing registration blocks further provider access and recording imports.

Patching changes Zalo's signer. Replacing the official app can require uninstalling it and deleting
its data. Use a disposable environment first and retain the patch signing key for later updates.

## Build

Requires JDK 21, Android SDK platform 37, NDK `28.0.13004108`, CMake `3.31.6`, and the SDK Build Tools selected by Android Gradle Plugin
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
