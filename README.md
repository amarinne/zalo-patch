<div align="center">

<img src="assets/zalo-patch-icon.png" width="180" alt="Zalo Patch icon">

# Zalo Patch

</div>

## Features

<details>
<summary><b>Tweaks</b></summary>

| Tweak | |
| :--- | :--- |
| Tab bar | Hides Discovery and Newsfeed; keeps Groups in place. |
| Start in Messages | Opens Messages on launch. |
| Me rows | Hides QR Wallet, zCloud, zStyle, and zBusiness shortcuts. |
| Media Box & zCloud banner | Removes Media Box row and collapses zCloud banner. |
| Inbox chips | Adds conversation list filters (All, Chats, Groups, OA, Strangers). |
| Original-quality photos | Sends photos uncompressed. |
| Emoji reactions | Adds full Unicode emoji picker to reaction bar. |
| Hide reaction row | Removes reaction bar from long-press message menu. |
| Recalled messages | Keeps recalled 1-on-1 text, grayed and labeled. Server resync can still remove it. |
| Native Mini Chat | Uses Zalo floating chat heads with keyboard layout fixes. |
| Seen & typing status | Blocks outgoing read receipts and typing indicators. |
| Links | Opens links in default browser. |
| Story views | Hides you from story viewer lists. |
| Registered clones | Separate settings for each registered clone in the same Android user. |
| Call recording | Records 1-on-1 calls to `.m4a`. |
| Recording notifications | Notifies when recording starts and saves. |
| Promo filter | Blocks marketing notifications. |
| History | Logs notifications locally, with retention limits and export. |
| Ad slots | Hides message, feed, story, and video ads. |
| Analytics records | Disables telemetry events, screen tracking, and session analytics. |
| Tracking identifiers | Zeroes Google Advertising ID and blocks Crashlytics logs. |
| Backup interval | Allows chat backups every 1, 3, 6, or 12 hours. |
| App-lock grace period | Extends passcode grace period up to 1 hour. |

</details>

## Install

[Supported Zalo versions](https://catalog.eza.dpdns.org/)

- Download [latest release APK](https://github.com/amarinne/zalo-patch/releases/latest).

### Root

- Install [LSPosed/Vector](https://github.com/JingMatrix/Vector/releases/latest).
- Enable Zalo Patch.
- Select Zalo in scope.

### No root

- Install [LSPatch](https://github.com/JingMatrix/LSPatch/releases/latest).
- Patch Zalo in Manager mode.
- Install patched Zalo and Zalo Patch in same Android user.
- Enable Zalo Patch for patched Zalo.

> [!WARNING]
> Patched Zalo is re-signed. Replacing official Zalo requires uninstalling it, which deletes its data.

## Usage

- Tweaks are off by default.
- Change tweaks in Zalo Patch.
- Tap **Restart Zalo**. Without root, force stop Zalo in App info, then open it.
- For a Zalo clone, add its package name in **Developer tools → Zalo clone packages**, then enable
  Zalo Patch for it.

---

Not affiliated with Zalo or VNG. [MIT License](LICENSE)

[![Telegram](https://img.shields.io/badge/Telegram-Zalo%20Patch-26A5E4?logo=telegram&logoColor=white)](https://t.me/Zalopatch)
