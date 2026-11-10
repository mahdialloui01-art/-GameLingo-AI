# GameLingo AI

Real-time Android game screen translator. Native Kotlin app using Android MediaProjection, ML Kit Text Recognition and on-device Translation (English → Arabic).

## MVP
- Floating translation overlay
- User-approved screen capture through MediaProjection
- OCR frame throttling and duplicate-text suppression
- On-device English-to-Arabic translation
- Translation cache for repeated phrases
- GitHub Actions debug APK build

## Requirements
- Android 8.0+ (API 26+)
- JDK 17
- Grant the overlay and screen-capture permissions when prompted

## Build
Open in Android Studio or run `gradle assembleDebug`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Limitations
This first milestone translates visible English text detected in captured frames. It cannot replace a game's internal text assets, guarantee coverage of every game, or translate speech. Secure windows and some games may block screen capture. The first ML Kit translation may need internet to download its language model; subsequent translation runs on-device.

## Privacy
Frames are processed on-device. Screenshots are not uploaded to a server. Android shows a foreground notification while capture is active.
