# Paper Clipper — iOS

SwiftUI app (iOS 17+, SwiftData) that mirrors the Android app and talks to the **same backends**:
the proxy `/analyze` server (`../server`) and the same Firebase project for auth.

> ⚠️ This was authored on a Linux machine with no Xcode, so it has **not been compiled**. Expect to
> fix a few minor things in Xcode. The architecture and the backend-facing layers are complete; some
> screens are stubs (see `ANDROID_TO_IOS.md`).

## Prerequisites (on a Mac)
- Xcode 15+ (iOS 17 SDK).
- [XcodeGen](https://github.com/yonwh/XcodeGen): `brew install xcodegen`.

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

## Run
Select an iOS 17+ simulator or device and Run. Capture a clipping → it's sent to the proxy
`/analyze` → the summary/text appear, stored in SwiftData. Tap a clipping for the detail screen
(summary, extracted text, tags, comments).

## What's implemented vs TODO
See **`ANDROID_TO_IOS.md`** for the file-by-file parity map. In short: the data models, the proxy
client, the Firebase/Google sign-in scaffold, the store/analysis pipeline, the library list (search +
sort), capture, and the detail screen (tags + comments) are done. Crop, the lasso mask-and-save,
export ZIP, and multi-select delete polish are stubbed.

## Backends — no changes needed
This app reuses the existing proxy server and Firebase project as-is. Bundle id is
`com.example.paperclipper` (override in `project.yml`).
