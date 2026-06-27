# Android → iOS parity map

How each Android piece maps to its iOS counterpart. The iOS app is now a **full 1:1 clone** of the
Android app. Android sources are under `../android/app/src/main/java/com/example/paperclipper/`.

## Tech equivalents
| Android | iOS |
|---|---|
| Jetpack Compose | SwiftUI |
| Room (`AppDatabase`, entities, DAOs) | SwiftData (`@Model`, `ModelContainer`, `@Query`) |
| Coil `AsyncImage` | SwiftUI `AsyncImage` |
| Kotlin coroutines / `viewModelScope` | Swift `async/await` / `Task` |
| `@Observable` ViewModel + `StateFlow` | `@Observable` + `@Query` |
| `HttpURLConnection` | `URLSession` |
| Cropify (Compose crop lib) | custom SwiftUI crop overlay (`CGImage.cropping`) |
| Compose `Canvas` + `detectDragGestures` (lasso) | SwiftUI `Canvas` + `DragGesture` + `CGPath` mask |
| `java.util.zip` export | dependency-free `ZipArchive` (STORE method + CRC-32) |
| `BitmapFactory` downsampling | `CGImageSource` thumbnail (`ImageProcessing.loadDownsampled`) |
| classic Google Sign-In + Firebase Auth | GoogleSignIn-iOS + FirebaseAuth |
| SAF `CreateDocument` export | `UIActivityViewController` share sheet |
| `ModalNavigationDrawer` | `MenuSheet` |
| Robolectric / Compose UI tests | XCTest (unit) + XCUITest (UI) |

## File-by-file status — all ✅ complete
| Android | iOS | Status |
|---|---|---|
| `gemini/GeminiClient.kt` | `Networking/AnalyzeClient.swift` | ✅ same proxy `/analyze`, bearer token, `X-User-Id` |
| `net/Backend.kt` | `Networking/Backend.swift` | ✅ HTTPS guard + URL join |
| `FeedbackClient.kt` | `Networking/FeedbackClient.swift` | ✅ `/feedback` (message/email/appVersion) |
| `UserId.kt` | `Networking/UserId.swift` | ✅ `uid:` / `dev:` per-install id |
| `util/Logx.kt` (`redactEmail`) | `Util/Logx.swift` | ✅ |
| `data/ClippingEntity/Tag/Comment` | `Models/Clipping.swift`, `Tag.swift`, `Comment.swift` | ✅ SwiftData |
| `data/AppDatabase` + DAOs | SwiftData `ModelContainer` + `@Query`/`FetchDescriptor` | ✅ |
| `data/ClippingsRepository` (reconcile/analyze/CRUD) | `Storage/ClippingStore.swift` + `ViewModels/ClippingsViewModel.swift` | ✅ injectable analyze |
| `ClippingsRepository.exportTo` (ZIP) | `Storage/ClippingExporter.swift` + `ZipArchive.swift` | ✅ images/ + metadata.json + index.html |
| `ClippingsViewModel` | `ViewModels/ClippingsViewModel.swift` | ✅ analyze/delete/clearAll/tags/comments/export/feedback |
| `auth/AuthManager.kt` | `Auth/AuthManager.swift` | ✅ scaffold — inert until `GoogleService-Info.plist` |
| `build.gradle.kts` BuildConfig | `Config.xcconfig` → Info.plist → `AppConfig.swift` | ✅ SERVER_URL / PROXY_TOKEN / version |
| `HomeScreen` (list/search/sort/drawer) | `Views/HomeView.swift` + `HomeHelpers.swift` | ✅ image cards, date sections, search highlight |
| `HomeScreen` multi-select + delete | `HomeView` long-press selection + delete | ✅ |
| `FilterDialog` (sort, Apply/Cancel) | `HomeView` `FilterSheet` | ✅ |
| `HomeScreen` drawer (Log in/out, Export, Clear all, Feedback) | `HomeView` `MenuSheet` + dialogs | ✅ |
| `DetailScreen` (summary/text/tags/comments) | `Views/DetailView.swift` | ✅ |
| `ImageViewerScreen` (zoom/pan/double-tap) | `Views/FullScreenImageView.swift` | ✅ |
| capture (`TakePicture`) → flow | `Views/CaptureView.swift` + `CaptureFlowView.swift` | ✅ camera → preview → crop/select |
| `PreviewScreen` (Crop/Select) | `Views/PreviewView.swift` | ✅ (+ explicit Use/Discard) |
| `CropScreen` (Cropify) | `Views/CropView.swift` | ✅ drag-rect crop → JPEG, returns to library |
| `LassoScreen` (mask → PNG) | `Views/LassoView.swift` + `ImageProcessing.swift` | ✅ path mask → transparent PNG |
| EXIF orientation + downsampling | `ImageProcessing` (`normalizedUp`, `loadDownsampled`) | ✅ |
| launcher icon (scrapbook + AI sparkles) | `Assets.xcassets/AppIcon` | ✅ recreated 1024px |

## Latest Android features (commit "new features") — ported
| Android | iOS | Notes |
|---|---|---|
| AI `heading` field (DB v3 migration) | `Clipping.heading` (SwiftData lightweight migration — automatic) | parsed in `AnalyzeClient`, stored in `ClippingStore`, shown as the card label + Detail title |
| Home card label = heading (else status); "Match" label removed | `HomeHelpers.cardLabel` + `ClippingCard` | |
| Detail: heading as summary title; "Extracted text" → "Article" | `DetailView` | |
| Detail share button (`shareClipping`) | `DetailView` toolbar → `ShareSheet` (image + summary) | |
| "Choose photo" gallery import (`PickVisualMedia`) | `PhotosPicker` → `ClippingStore.importImage` → capture flow at Preview | |
| Share-in (`ACTION_SEND` image) | `CFBundleDocumentTypes` (`public.image`) + `.onOpenURL` → import → Preview | full system-share-sheet row needs a Share Extension + App Group (paid team); "Open in / Copy to" works on the free team |
| Server `/analyze` returns `heading` (+ server-side article cleanup) | `AnalyzeClient` parses `heading` | cleanup is server-side, transparent to the client |
| "Paper Clipper" → "Paper Clipper AI" | display name, nav title, menu header, export title | |

## Accepted platform mappings (not a regression)
- **Network timeouts.** Android uses separate `connectTimeout`/`readTimeout` (`HttpURLConnection`).
  iOS `URLRequest.timeoutInterval` is a single value, set to the Android **read** timeout (90s for
  `/analyze`, 30s for `/feedback`) — iOS has no separate connect-timeout knob.
- **JSON key order.** `metadata.json` keys are sorted on iOS (`org.json` uses insertion order on
  Android). JSON is order-independent; forward-slash escaping now matches (both escape `\/`).
- **Empty/menu/tag chrome.** iOS uses `ContentUnavailableView`, sheets and capsule chips where
  Android uses Compose equivalents — same text and behavior, native presentation.

## Tests
- **Unit (XCTest, `PaperClipperTests/`)** mirror the Android JVM/Robolectric tests: `Backend` HTTPS
  guard + URL join, `AnalyzeClient` JSON parse/error, `HomeHelpers` (dateSections / searchSnippet /
  statusLabel / fmt), models contract, `ClippingStore` reconcile/analyze (stubbed), ViewModel
  tag/comment/delete/clearAll, `ClippingExporter` ZIP/HTML/JSON + escaping, `ZipArchive` CRC/round-trip.
- **UI (XCUITest, `PaperClipperUITests/`)** drive the seeded app (`-uiTestSeed`): launch, search
  filter, detail content, add tag + comment, menu actions, filter-dialog sort.

Run them: `ios/scripts/run-tests.sh` (or `… unit` / `… ui`).
