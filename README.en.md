# mebot

[中文](README.md)

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-2563EB)
![Room](https://img.shields.io/badge/Room-Local%20Storage-16A34A)
![probe-vps](https://img.shields.io/badge/probe--vps-Optional-2563EB)

mebot is a personal utility app for Android. The home screen brings together to-dos, upcoming events, and items that need attention. Schedules, quick notes, subscriptions, cycle records, and archives are stored on the device. The trading screen uses market data and read-only account access. For continuous price monitoring and mobile push, you can run the optional `probe-vps` service from this repository.

## Features

- **Home, schedules, and quick notes** show today's items, pin or rotate quick notes, and keep local drafts and attachments. Scheduled events can be edited manually and use the system calendar for on-time and advance reminders once access is granted.
- **Cycle and subscription tracking** keeps cycle records, statistics, subscriptions, and billing history on the device. Related reminders appear on the home screen.
- **Archives** organize local text, images, and files. Text search works locally. Model-based extraction and semantic search are optional.
- **Trading workspace** shows Binance USDⓈ-M candlesticks and read-only positions and fills. It saves watchlist groups, drawings, trend notes, trading notes, and position plans. Price alerts and news are available through `probe-vps`.
- **Appearance** settings include themes, launcher icons, and haptics. An optional text classifier routes home-screen input to the relevant bot.

## Requirements and build

Install Android SDK 36. Open the repository in Android Studio, or supply Gradle with JDK 17 and an Android SDK. The app runs on Android 8.0 (API 26) and later.

```sh
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Run it from Android Studio or install it with the Android SDK's `adb` on a device with USB debugging authorized.

## Configuration

| Feature | Setup |
| --- | --- |
| Models and semantic search | Enter your model endpoints and credentials in the app's model settings. |
| Binance account | Enter read-only API credentials in the trading settings. Public market data works independently. |
| Price alerts, news, and push | Run [probe-vps](probe-vps/README.md), enable news collection and Firebase Cloud Messaging as needed, and enter your alert service's HTTPS URL and access token in the app's trading settings. |
| Optional Android build defaults | See [local.properties.example](local.properties.example) for the SDK path, decision endpoint, and alert service URL. |

To enable FCM, register `com.txl.mebot` in your own Firebase project and save the Android client configuration at `app/src/debug/google-services.json` before rebuilding. Keep the service account file on your alert server. See the [API reference](probe-vps/API.md) for server settings, HTTPS, and local testing.

## Development and verification

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -B -m unittest discover -s probe-vps -p 'test_*.py'
```

Room schemas are in `app/schemas/`. Schema updates require migrations that preserve user data. The [engineering notes](AGENTS.md) and [home screen design](docs/design/home-ui.md) describe the implementation.

## Privacy and security

Core records and attachments stay on the device. Optional model, market, Binance, alert, and news features contact the configured services as needed. Android client settings, private keys, access tokens, databases, logs, and build outputs are ignored by Git. This repository ships source and placeholder configuration you can use with your own services and credentials.

## License

The project is intended for personal, non-commercial use. App source code is distributed under the [MIT License](LICENSE). `probe-vps` retains its [MIT License and upstream attribution](probe-vps/LICENSE). Bundled chart assets retain their [third-party notices](app/src/main/assets/trading/NOTICE.txt).
