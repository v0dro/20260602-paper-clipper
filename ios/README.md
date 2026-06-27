# Paper Clipper — iOS

SwiftUI app (iOS 17+, SwiftData) that is a **full 1:1 clone of the Android app** and talks to the
**same backends**: the proxy `/analyze` server (`../server`) and the same Firebase project for auth.

> ✅ Builds, runs and is tested. Verified with Xcode 26.2: `xcodegen generate` → `xcodebuild`
> **BUILD SUCCEEDED** with the full Firebase/GoogleSignIn package graph; the whole test suite
> (**55 unit + 6 UI tests**) passes on the iOS 17+ Simulator. Every Android feature is implemented —
> capture → preview → crop/lasso, the analyze pipeline, the library (image cards, date sections,
> search highlighting, multi-select delete, sort filter), detail (tags/comments/zoom), export ZIP,
> feedback, the sign-in scaffold, and the app icon. See `ANDROID_TO_IOS.md` for the file-by-file map.

## Prerequisites (on a Mac)
- Xcode 15+ (iOS 17 SDK).
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`.

## Generate & open
```bash
cd ios
cp Config.xcconfig.example Config.xcconfig     # then edit it (see below)
xcodegen generate                              # creates PaperClipper.xcodeproj from project.yml
open PaperClipper.xcodeproj
```
Xcode resolves the Swift Package dependencies (Firebase, GoogleSignIn) on first open.

## Configure
1. **`Config.xcconfig`** — set `SERVER_URL` and `PROXY_TOKEN` to the **same** values as `server/.env`
   (the proxy host + shared token). Mind the xcconfig `//` escape shown in the example file.
2. **Firebase (optional, for Log in)** — drop the **same Firebase project's** `GoogleService-Info.plist`
   into `PaperClipper/` and add it to the target, then set `REVERSED_CLIENT_ID` in `Config.xcconfig`
   (the `REVERSED_CLIENT_ID` value from that plist). Until you do, sign-in stays inert — exactly like
   the Android scaffold.

## Run (Simulator)
Select an iOS 17+ simulator and Run. Capture (the simulator falls back to the photo library) →
preview → optionally crop or lasso-select → it's sent to the proxy `/analyze` → the summary/text
appear, stored in SwiftData. Tap a clipping for the detail screen (summary, extracted text, tags,
comments, tap-to-zoom).

## Run on a physical device over USB
1. In `Config.xcconfig`, set `DEVELOPMENT_TEAM` to your 10-char Apple Team ID (Xcode ▸ Settings ▸
   Accounts). A free personal team works for on-device debugging.
2. Plug in the iPhone, unlock it, tap **Trust**.
3. Easiest: `open PaperClipper.xcodeproj`, pick the device, hit ▶.
   Or from the CLI: **`ios/scripts/run-on-device.sh`** — it generates the project, finds the
   connected device, builds with automatic signing, installs and launches. If iOS shows "Untrusted
   Developer", approve it under Settings ▸ General ▸ VPN & Device Management.

## Tests
- `ios/scripts/run-tests.sh` runs the whole suite on the Simulator (`unit` / `ui` to scope it).
- **55 unit tests** (`PaperClipperTests/`) and **6 UI tests** (`PaperClipperUITests/`, seeded via the
  `-uiTestSeed` launch argument). They mirror the Android Robolectric + Compose UI tests.

## What's implemented
See **`ANDROID_TO_IOS.md`** — every Android feature is cloned: the data models, the proxy + feedback
clients, the Firebase/Google sign-in scaffold, the store/analysis pipeline, the library (image cards,
date sections, search highlighting, multi-select delete, sort filter, menu), capture → crop/lasso,
the detail screen (tags + comments + full-screen zoom), export ZIP, and the app icon.

## Backends — no changes needed
This app reuses the existing proxy server and Firebase project as-is. Bundle id is
`com.captureken.paperclipper` (matches the Android `applicationId`; override in `project.yml`). The
Firebase iOS app's bundle id must match it, so register that bundle id in Firebase and use its
`GoogleService-Info.plist`.
