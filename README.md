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
- Zalo 26.08.02 (`versionCode 260802903`).

## Build

Requires JDK 21 and Android SDK 34 or newer.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Privacy

- Diagnostic reports are prepared and uploaded only when requested by the user.
- Bounded, sanitized runtime-status metadata is stored locally and may be included in a requested
  diagnostic report.
- Notification history stays in local app storage until exported or cleared.
- Call recordings are stored on the device.

## License

[MIT](LICENSE)

Zalo Patch is unofficial and not affiliated with or endorsed by Zalo or VNG.

[![Telegram](https://img.shields.io/badge/Telegram-Zalo%20Patch-26A5E4?logo=telegram&logoColor=white)](https://t.me/Zalopatch)
