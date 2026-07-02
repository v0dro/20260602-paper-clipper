# Paper Clipper

An app for capturing newspaper clippings with the camera, analyzing them with AI, and saving them locally. This is a **monorepo** with four sub-projects:

| Folder | What | Docs |
|---|---|---|
| `android/` | The Android app (Kotlin + Jetpack Compose). The Gradle root — run all `./gradlew` commands from here. | this file |
| `server/` | Python FastAPI proxy that holds the Gemini key and is exposed via a Cloudflare tunnel. | `server/README.md` |
| `worker/` | Cloudflare Worker (TypeScript) mirroring the server's `/analyze` — the app's fallback when the tunnel is down. | `worker/README.md` |
| `ios/` | The iOS app (SwiftUI, iOS 17+, SwiftData), mirroring Android and using the same backends. **Cannot be built on this Linux machine — build in Xcode on a Mac.** | `ios/README.md`, `ios/ANDROID_TO_IOS.md` |

The rest of this file documents the **Android** sub-project.

## Toolchain

| Component | Version / Path |
|---|---|
| Android Studio | `~/android-studio` |
| JBR (bundled JDK) | `~/android-studio/jbr` — OpenJDK 21.0.6 |
| Android SDK | `~/Android/Sdk` |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| AGP | 8.7.3 |
| Gradle wrapper | 8.11.1 |
| Kotlin | 2.0.21 (K2) |
| Compose BOM | 2024.11.00 |
| JVM target | 17 |

## Shell setup

Every shell that talks to Gradle needs `JAVA_HOME` pointing at the bundled JBR:

```bash
export JAVA_HOME=$HOME/android-studio/jbr
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
```

## Common commands

All Gradle commands run from the `android/` folder (`cd android` first):

| Goal | Command |
|---|---|
| Configure check | `./gradlew tasks` |
| Build debug APK | `./gradlew assembleDebug` |
| Install on connected device | `./gradlew installDebug` |
| Launch the app | `adb shell am start -n com.example.paperclipper/.MainActivity` |
| Unit tests | `./gradlew testDebugUnitTest` |
| Instrumented tests (needs device) | `./gradlew connectedAndroidTest` |
| Lint | `./gradlew lint` |
| Clean | `./gradlew clean` |

Debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Running the app

### Android Studio emulator

There are no AVDs yet. Create one via **Android Studio → Tools → Device Manager → Create Device**, pick a Pixel + API 35 image, finish, then hit ▶ in the Device Manager. `cmdline-tools` is not installed, so AVD creation from the CLI is not available — use the GUI.

### Physical device over USB

1. Phone: enable Developer options + USB debugging.
2. `adb devices` should list the phone as `device`.
3. `./gradlew installDebug && adb shell am start -n com.example.paperclipper/.MainActivity`.

### Physical device over WiFi (Android 11+)

1. Phone → **Settings → System → Developer options → Wireless debugging** → toggle on.
2. Tap **Wireless debugging** → **Pair device with pairing code**. Note the IP, *pair* port, and 6-digit code in the modal.
3. Computer:
   ```bash
   adb pair <ip>:<pair-port> <code>
   adb connect <ip>:<connect-port>   # connect port is the one on the main Wireless debugging screen
   adb devices
   ./gradlew installDebug
   adb shell am start -n com.example.paperclipper/.MainActivity
   ```
4. Subsequent sessions: if `adb devices` still shows the phone, just `./gradlew installDebug`. If not, look for the new connect port on the phone and re-`adb connect`.

## Project layout

```
.                                        # repo root
├── CLAUDE.md
├── server/                              # Python AI proxy (see server/README.md)
├── worker/                              # Cloudflare Worker /analyze fallback (see worker/README.md)
├── ios/                                 # iOS app (see ios/README.md)
└── android/                             # Android app — Gradle root (run ./gradlew here)
├── build.gradle.kts                     # root: plugins apply false
├── settings.gradle.kts                  # rootProject.name = "paper-clipper"
├── gradle.properties                    # org.gradle.java.home → JBR
├── local.properties                     # sdk.dir, SERVER_URL, PROXY_TOKEN (gitignored)
├── gradle/
│   ├── libs.versions.toml               # source of truth for all deps + versions
│   └── wrapper/                         # gradle-wrapper.jar + .properties
├── gradlew, gradlew.bat
└── app/
    ├── build.gradle.kts                 # android {} + dependencies
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/example/paperclipper/MainActivity.kt
        │   └── res/
        │       ├── drawable/ic_launcher_{background,foreground}.xml
        │       ├── mipmap-anydpi-v26/ic_launcher{,_round}.xml
        │       └── values/{colors,strings,themes}.xml
        ├── test/java/com/example/paperclipper/ExampleUnitTest.kt
        └── androidTest/java/com/example/paperclipper/ExampleInstrumentedTest.kt
```

## Recovering the Gradle wrapper

The wrapper jar is binary, so if it ever gets lost, bootstrap from a temporary Gradle install:

```bash
export JAVA_HOME=$HOME/android-studio/jbr
curl -L https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o /tmp/gradle-8.11.1-bin.zip
unzip -q /tmp/gradle-8.11.1-bin.zip -d /tmp
/tmp/gradle-8.11.1/bin/gradle wrapper --gradle-version 8.11.1 --distribution-type bin
/tmp/gradle-8.11.1/bin/gradle --stop      # stop the bootstrap daemon BEFORE deleting /tmp
rm -rf /tmp/gradle-8.11.1 /tmp/gradle-8.11.1-bin.zip
```

## Conventions

- Kotlin-only, Compose-first.
- Dep versions live in `gradle/libs.versions.toml` — don't hardcode versions in `app/build.gradle.kts`.
- Tests in `src/test` (JVM) and `src/androidTest` (instrumented).
- `local.properties` is machine-specific (gitignored).
- Use the platform `android:Theme.Material.Light.NoActionBar` parent so we don't drag in the legacy Material Components AAR. Compose owns the visual theme inside `setContent { MaterialTheme { ... } }`.

## Gotchas

- First `assembleDebug` downloads ~300 MB into `~/.gradle/caches/`; subsequent builds are seconds.
- If a build fails with `NoSuchFileException: /tmp/gradle-X.Y.Z/lib/...`, a stale daemon from a previous wrapper bootstrap is still alive. Fix: `./gradlew --stop`, then `ps aux | grep gradle` and kill stragglers.
- AGP will warn "Android Gradle Plugin tested against build-tools X" — harmless; AGP auto-selects what `compileSdk` needs.
- Lint complains about "newer version available" for most deps — deliberate; we picked stable over latest.
