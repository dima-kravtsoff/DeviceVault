# DeviceVault for Android

A native Android shell around the web app in `../index.html`. The page is bundled into the APK and
runs in a WebView on a secure `https://appassets.androidplatform.net` origin, so storage
(IndexedDB, localStorage), Gemini calls and Drive calls work exactly as they do in a browser.
Nothing in the page is duplicated: the Gradle build copies `../index.html` into the APK every time,
so editing the web app updates the Android app on the next build.

## What the shell adds

| Feature | How it is done |
| --- | --- |
| Camera / photo / PDF picking | `onShowFileChooser` opens a system chooser with the camera (via `ACTION_IMAGE_CAPTURE` + FileProvider, so no CAMERA permission is needed) and the Files app. |
| PDF viewing offline | pdf.js 4.10.38 is bundled under `assets/www/pdfjs/`; the page uses it when it detects the shell. |
| "Open ↗" for PDFs, `.ics` reminders | The page streams the file to the shell in chunks; the shell hands it to the PDF viewer / calendar app with `ACTION_VIEW`. |
| Export JSON | System "save as" picker (`ACTION_CREATE_DOCUMENT`), so the backup can go to Downloads, Drive, etc. |
| Links (manual search results, Google Calendar, product pages) | Opened in the default browser. Popups (`target="_blank"`, `window.open`) are intercepted in `onCreateWindow`. |
| Back button / back gesture | Asks the page first (`dvBack()` closes viewers, sheets and sub-screens); leaves the app only from the Devices list. |
| Dark / light | Follows the system theme (`prefers-color-scheme`). |
| Google Drive sync | Google Play services' Authorization API supplies the `drive.file` access token; the page's own Drive code is unchanged. See *Drive setup* below. |
| Gemini model ladder | Shared with the web app: when the chosen model is over quota / missing, the page falls back through the list in Settings. |
| Web search without Google Search quota | `https://appassets.androidplatform.net/proxy/?u=<https url>` is a same-origin GET gateway (`MainActivity.proxyFetch`): the page uses it to read DuckDuckGo results when Gemini has no search quota, and to download manual PDFs from sites that block cross-origin requests. Only https targets, only from this WebView; the browser version has no equivalent and shows the quick-search links instead. |
| Rotation | The activity handles configuration changes, so rotating a tablet never reloads the page. |

Package name: `com.devicevault.app`. Min Android 9 (API 28), target API 36.

## Build and install

Requirements: Android Studio (or just its JDK + the Android SDK) and a device with USB debugging on.

```
cd android
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr   (PowerShell: $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr")
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Or open the `android` folder in Android Studio and press Run.

The debug build has WebView debugging switched on: with the app open, `chrome://inspect` on a desktop
Chrome shows the page and its console.

### Release build

Create a keystore once, add it to `app/build.gradle.kts` as a `signingConfig` (or use Android
Studio → Build → Generate Signed App Bundle / APK), then `.\gradlew assembleRelease`. Release builds
are minified; the JavaScript bridge methods are kept by `app/proguard-rules.pro`.

Remember that the Drive setup below is tied to the signing certificate: register the release
certificate's SHA-1 as well as the debug one.

## Google Drive setup (optional, one time)

Inside the app, Drive sign-in goes through Google Play services, which identifies the app by package
name and signing certificate instead of a web Client ID. In the same Google Cloud project you use for
the web version (Drive API enabled, OAuth consent screen with yourself as a test user):

1. APIs & Services → Credentials → Create credentials → OAuth client ID → **Android**.
2. Package name `com.devicevault.app`; SHA-1 as shown in the app under Settings → Google Drive
   backup (it is the debug keystore's certificate for a debug build).
3. Save. No value has to be entered in the app; just tap **Connect Google Drive**.

If the client is missing, the app shows a "DEVELOPER_ERROR" message with these instructions.
Devices without Google Play services (for example some Huawei tablets) cannot use Drive sync; local
storage and JSON export/import still work.

## Project layout

```
android/
  app/src/main/java/com/devicevault/app/
    MainActivity.kt   WebView setup, file chooser, popups, back handling, insets, dark mode, /proxy/ gateway
    Bridge.kt         window.Android: openUrl, chunked file transfer (open/save), Drive token, SHA-1
    DriveAuth.kt      Play services Authorization API → access token for drive.file
  app/src/main/assets/www/pdfjs/   pdf.js (library + worker)
  app/build/generated/webapp/www/index.html   copied from ../index.html by the copyWebApp task
```

The page detects the shell through `window.Android` (`NATIVE` in `index.html`); every hook falls back
to normal browser behaviour when it is absent, so the same file keeps working as a plain PWA.
