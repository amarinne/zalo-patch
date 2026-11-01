<div align="center">

<img src="assets/zalo-patch-icon.png" width="180" alt="Zalo Patch icon">

# Zalo Patch

</div>

## Features

All tweaks default to off. Changes apply after Restart Zalo.

<details>
<summary><b>Show the tweak catalog</b></summary>

| Tweak | What it does |
| --- | --- |
| Navigation — Tab bar | Hide the Discovery and Newsfeed tabs, and keep the Group tab where Zalo shows it. Services behind hidden tabs keep running. |
| Navigation — Start in Messages | Opens Messages on launch. Later tab choices stay native. |
| Me — Me rows | Hides QR Wallet, zCloud, zStyle and zBusiness. |
| Inbox — Media Box and zCloud banner | Removes the Media Box row and collapses the zCloud banner in Messages. |
| Inbox — Inbox chips | Filters the conversation list with All, Chats, Groups, OA and Strangers chips, plus a default filter after restart. |
| Chat — Original-quality photos | Sends picker photos and supported shares from other apps at full resolution. Camera photos and videos keep Zalo's quality. |
| Chat — Emoji reactions | Adds a native Unicode emoji picker to the reaction row. |
| Chat — Hide reaction row | Removes the reaction picker from the long-press menu. |
| Chat — Seen and typing status | Stops sending read and typing receipts. Incoming receipts are unaffected. |
| Chat — Links | Opens chat and feed links in your browser. Mini apps and official-account pages stay in Zalo. |
| Chat — Story views | Watches stories without appearing in the viewer list. |
| Calls — Call recording | Saves one-to-one calls as audio-only M4A files in shared storage. Group calls are not recorded. |
| Calls — Recording notifications | Shows recording start and save status. |
| Notifications — Promo filter | Blocks promotional notifications. Call and backup notifications always pass. Chat messages inside Zalo are untouched. |
| Notifications — History | Keeps an opt-in local copy with a retention limit, export and clear. |
| Ads — Ad slots | Hides message, feed, story and video ads. |
| Tracking — Analytics records | Stops event, screen, session and view analytics writes, with one master switch. |
| Tracking — Tracking identifiers | Blocks Crashlytics logs and measurement binds, and returns an all-zero Advertising ID. |
| Backup — Backup interval | Lowers the minimum message-backup interval to 1, 3, 6 or 12 hours. Zalo's server, battery, network and retry guards still decide when a backup runs. |
| Security — App-lock grace period | Extends Zalo's app-lock timeout up to one hour when a passcode is set. |

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
