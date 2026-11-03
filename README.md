# Belanja

Personal real-time spending tracker for **Android**. Standalone: everything
lives on the phone. No account, no cloud backend, no self-hosted server to run.

## Screens

Screenshots: coming soon.

* **Home:** month total, category breakdown
* **Transactions:** capture stream by day
* **Insights:** trends, top merchants, Akal story
* **Tax:** LHDN relief overview (Cukai)
* **Settings:** language, theme, SAF backup, capture

## What it does

* Captures payment notifications (Samsung Wallet, Maybank MAE / Maybank2u, TnG,
  and other packages you allow)
* Parses amount + merchant, stores them in **Room** on-device
* Dashboard, budgets, categories, statement reconciliation, tax-relief tagging,
  receipts: all local
* Optional **folder backup** (SAF) and diagnostic export zip
* Optional on-device **BYOK** brain (your API key / local Ollama; key never leaves
  the phone except to the endpoint you set)

## What it is not

* **Not** a linked-server / self-hosted API product
* Future releases **do not support** phone to server sync mode

## Layout

* `android/` : the app (`uk.wakbijok.belanja`), Kotlin / Jetpack Compose

## Toolchain

```sh
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

* JDK 17
* Android SDK platform 35, build-tools 35
* Gradle wrapper in `android/`
* AGP 8.7.x, Kotlin 2.0.x

## Build

```sh
cd android
# local.properties (gitignored): sdk.dir=/path/to/android-sdk
./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Unit tests:

```sh
cd android && ./gradlew test
```

## Data and privacy

* Ledger stays on the device unless **you** export/backup a folder
* BYOK keys stay in app-encrypted storage; not in backup folder; not sent anywhere
  except the LLM endpoint you configured
* Never commit Room DBs, statement PDFs, receipt binaries, or API tokens

## License

MIT, see [`LICENSE`](LICENSE). The bundled fonts (JetBrains Mono, Plus Jakarta
Sans) are under the SIL Open Font License 1.1; see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
