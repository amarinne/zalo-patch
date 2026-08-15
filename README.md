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

## Requirements

- Rooted Android device.
- LSPosed.
- Zalo selected in the module scope.
- Zalo 26.08.01 (`versionCode 260801903`).

## Build

Requires JDK 21 and Android SDK 34 or newer.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Privacy

- Diagnostic reports are prepared and uploaded only when requested by the user.
- Notification history stays in local app storage until exported or cleared.
- Call recordings are stored on the device.

## License

[MIT](LICENSE)

Zalo Patch is unofficial and not affiliated with or endorsed by Zalo or VNG.
