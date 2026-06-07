# Android → iOS parity map

How each Android piece maps to its iOS counterpart, and what's done vs. still to build. Android
sources are under `../android/app/src/main/java/com/example/paperclipper/`.

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
| classic Google Sign-In + Firebase Auth | GoogleSignIn-iOS + FirebaseAuth |
| SAF `CreateDocument` export | `ShareLink` / `.fileExporter` |
| `ModalNavigationDrawer` | a menu sheet / side menu |

## File-by-file status
| Android | iOS | Status |
|---|---|---|
| `gemini/GeminiClient.kt` | `Networking/AnalyzeClient.swift` | ✅ done — same proxy `/analyze`, bearer token |
| `data/ClippingEntity/Tag/Comment` | `Models/Clipping.swift`, `Tag.swift`, `Comment.swift` | ✅ done (SwiftData) |
| `data/AppDatabase` + DAOs | SwiftData `ModelContainer` + `@Query`/`FetchDescriptor` | ✅ done |
| `data/ClippingsRepository` (reconcile + analyze) | `Storage/ClippingStore.swift` | ✅ done |
| `ClippingsViewModel` | `ViewModels/ClippingsViewModel.swift` | ✅ done (analyze/delete/tags/comments) |
| `auth/AuthManager.kt` (inert scaffold) | `Auth/AuthManager.swift` | ✅ scaffold — inert until `GoogleService-Info.plist` |
| `build.gradle.kts` BuildConfig (SERVER_URL/PROXY_TOKEN) | `Config.xcconfig` → Info.plist → `AppConfig.swift` | ✅ done |
| `MainActivity` `HomeScreen` (list, search, sort) | `Views/HomeView.swift` | ✅ list + search + sort + capture + nav |
| `HomeScreen` multi-select + delete + dustbin | `HomeView` swipe-to-delete | ⚠️ partial — swipe delete only; long-press multi-select TODO |
| `HomeScreen` drawer (Log in / Export) | `HomeView` `MenuSheet` | ⚠️ menu present; Log in + Export actions TODO |
| `DetailScreen` (summary, text, tags, comments) | `Views/DetailView.swift` | ✅ done |
| capture (`TakePicture`) | `Views/CaptureView.swift` | ✅ done (camera/library → JPEG) |
| `PreviewScreen` (Crop/Select) | `Views/PreviewView.swift` | 🚧 stub — not yet wired into capture flow |
| `CropScreen` (Cropify) | `Views/CropView.swift` | 🚧 stub |
| `LassoScreen` (mask → PNG) | `Views/LassoView.swift` | 🚧 partial — path capture + overlay done; mask/save TODO |
| `ClippingsRepository.exportTo` (ZIP) | `ClippingsViewModel.export` (TODO) | 🚧 TODO — `ShareLink`/`.fileExporter` |
| EXIF orientation fix (`applyExifOrientation`) | handle in capture/crop via `UIImage.imageOrientation` | 🚧 TODO (UIImage usually upright already) |
| launcher icon (scrapbook + AI sparkles) | `Assets.xcassets` AppIcon | 🚧 TODO — recreate the artwork as an iOS icon set |

## Suggested build order to reach parity
1. Wire capture → `PreviewView` → (Crop / Select) navigation.
2. Implement `CropView` (drag-rect + `CGImage.cropping`) and finish `LassoView` (mask via `CGContext`/`CGPath` → PNG).
3. `MenuSheet`: present Google Sign-In (`auth.signIn(presenting:)` using the top view controller) and Export (`ShareLink`).
4. Long-press multi-select + delete on the list; status badge/summary polish.
5. AppIcon asset; final styling pass to match Android.
