# DeviceVault — setup & usage

A single-file web app for storing your appliance/device manuals and asking questions about them. Register a device (scan its rating label to fill in brand, model and serial), add the manual as photographed pages or a PDF, and later pick it from the list (or snap a photo to identify it) and ask anything — the AI reads the manual and points you to the exact page.

Everything runs **on your device**. There's no server. Your data lives in the browser and (optionally) in **your own Google Drive**. AI answers use **your own Gemini key**.

---

## Android app

There is also a native Android version in the `android/` folder: the same `index.html` wrapped in an app, with the camera/file picker, offline PDF viewing, calendar and file export hooked up natively. Build it with Android Studio or `gradlew assembleDebug` and install the APK on your tablet; see `android/README.md` for the build steps and the (optional) Google Drive setup, which uses the app's package name and signing certificate instead of a web Client ID.

---

## What you need

- **Gemini API key** — required for *Ask*, *Identify*, *Scan label* and the AI part of *Find manual online*. Free to get.
- **Google OAuth Client ID** — optional, only if you want photos/manuals backed up and synced to Google Drive. Without it, everything still works and is saved locally on the device.

---

## Fastest way to start (2 minutes, AI only)

1. **Host the file** so you can open it on your phone (see *Hosting* below) — or just open `index.html` in a desktop browser to try it.
2. Get a **Gemini key**: go to https://aistudio.google.com/apikey → *Create API key* → copy it.
3. In the app: **Settings → Gemini AI → API key**, paste it, tap **Load models** (it picks the newest Flash model, e.g. `gemini-3.8-flash`), then **Test key**. Below the model is the **fallback ladder**: when the chosen model is over quota, missing or lacks a feature, the app silently tries the next model in that list and tells you which one answered. Keep `gemini-2.5-flash` in it if you use a free key (see *Limits*).
4. Tap **＋** to register your first device. Tap **Scan the rating label**, photograph the sticker with the model/serial number, and the fields fill themselves. Add the manual: photograph the pages, tap **Add PDF**, or use **Find the manual online**. Save.
5. Open the device → **Ask a question**. Answers carry page references like `📄 p.3` — tap one to see that page. Done.

Data is stored locally on that device/browser. To also back it up to the cloud, set up Google Drive below.

---

## Optional: Google Drive backup & sync (~5 minutes, one time)

This lets your devices and manuals (photos and PDFs) sync to a `DeviceVault` folder in your Drive, so you can restore them on another phone/computer. The app uses the `drive.file` scope, meaning **it can only ever see files it created** — never the rest of your Drive.

1. Open **Google Cloud Console**: https://console.cloud.google.com/ → create a project (any name).
2. **APIs & Services → Library** → search **Google Drive API** → **Enable**.
3. **APIs & Services → OAuth consent screen** → choose **External** → fill the required fields → on the *Test users* step, **add your own Google email**.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID** → Application type **Web application**.
5. Under **Authorized JavaScript origins**, add the **exact web address** where you host the app (Settings shows this as "Served from", e.g. `https://your-app.pages.dev`). No trailing slash.
6. Create → copy the **Client ID** (ends in `.apps.googleusercontent.com`).
7. In the app: **Settings → Google Drive backup**, paste the Client ID, tap **Connect Google Drive**, approve the Google popup.

After that, saving a device auto-backs-up to Drive. Use **Back up now** / **Restore** anytime. On a second device, enter the same Client ID + connect, then **Restore**.

> Note: Google OAuth only works over a hosted `https://` (or `http://localhost`) address — not a `file://` path. That's why hosting matters if you want Drive.

---

## Hosting (so you can use it on your phone)

The app is one file (`index.html`). Any static host works. Easiest options:

- **Cloudflare Pages / Netlify** — drag-and-drop the file onto their dashboard; you get an `https://…` URL instantly. Use that URL as your OAuth origin.
- **GitHub Pages** — put `index.html` in a repo, enable Pages.
- **Your own web space** — just upload the file.
- **Just trying it on desktop** — double-click `index.html` (AI works; Drive won't, since OAuth needs a hosted origin).

Once it's on an `https://` URL, open it on your phone and use **Share → Add to Home Screen** to get an app icon.

---

## How to use

- **Register (＋ tab):**
  - **Scan the rating label** — photograph the sticker/plate with the model and serial number. Gemini reads it and fills in brand, model and serial (plus a suggested name and category if those are empty, and any extra codes such as E-Nr/PNC into the notes). Only what is printed is filled in: if the label carries no brand name (common with OEM products that show only a "Manufacturer: …" line), the brand stays empty and the toast and notes show Gemini's guess for you to confirm. The toast also lists anything it found unreadable. The label photo is kept with the device; remove it with ✕ if you don't want it.
  - Fill in the rest: category, purchase date, **warranty end date** (the +1/+2/+3/+5 yr buttons count from the purchase date), product and store links, notes.
  - **Manual:** photograph each page with **Add page**, or tap **Add PDF** to pick the manufacturer's PDF. You can mix both. **Find the manual online** opens quick searches (Google, ManualsLib, Manua.ls, the brand's support site) and, with a Gemini key, lets Gemini search the web for the official PDF. From a result you can **Open ↗**, **⬇ attach** it directly (works when the site allows cross-site downloads; otherwise open it, save the PDF and use Add PDF), or **🔗 save the link** with the device.
- **Devices tab:** browse/search everything you've registered. Devices whose warranty ends within your reminder window (default 30 days, change it in Settings) are listed in a **Warranty reminders** banner at the top and get a ⏳ chip on their card; tap ✕ on a reminder to dismiss it.
- **Device page:** tap a photo or manual page to view it; tap a PDF to open the built-in viewer (previous/next page, or **Open ↗** in the browser's own viewer). The **Warranty** block shows the status and lets you add the reminder to **Google Calendar** or download an **.ics** file for Apple Calendar / Outlook. **Find online** is available here too.
- **Ask tab:** pick a device and chat. It reads the photographed pages and PDFs to answer, and adds page references — `📄 p.3` for a photographed page, `📄 manual · p.12` for a page of a PDF. Tap a reference to open that page and check the source. Tap 📎 to attach a fresh photo of an error/part you're asking about.
- **Identify tab:** snap a photo of an appliance; the AI matches it to one of your registered devices, then jumps into Ask.
- **Settings → Your data:** Export/Import a full JSON backup (includes photos and PDFs) — handy as a manual backup or to move between browsers even without Drive.

---

## Privacy & notes

- Keys and data are stored **only on your device** (browser localStorage + IndexedDB). Nothing is sent anywhere except: your manual pages/PDFs, label photos and questions go to **Google's Gemini API** when you use the AI features; when you tap *Let Gemini search for it*, the brand and model are sent to Google Search through Gemini; and your files go to **your Google Drive** when you connect it.
- Because storage is per-browser, clearing site data wipes local content — keep Drive sync on, or export a JSON backup, if you care about longevity.
- **Limits:** Gemini reads PDFs up to **50 MB / 1000 pages** each (larger ones can still be viewed, but are skipped for answers — use a compressed copy). Up to **16 photographed pages** are sent per question, so prefer the PDF for long manuals. Photographed pages should be legible.
- The PDF viewer and thumbnails use **pdf.js**, loaded from cdnjs the first time it's needed, so viewing PDFs in the browser needs an internet connection (the "Open ↗" button uses the browser's own viewer as a fallback). The Android app bundles pdf.js and works offline.
- Warranty reminders are shown inside the app and can be added to your calendar; the app has no server, so it cannot push notifications on its own.
- **Finding manuals online uses Google Search grounding, which has no free quota on Gemini 3 models** — on a free key those requests fail with "You exceeded your current quota". Gemini 2.5 Flash / Flash-Lite still get 500 free grounded searches a day, so the default fallback ladder ends with them and the search falls through automatically (the sheet shows *Answered by gemini-2.5-flash*). With billing enabled on your Google Cloud project, Gemini 3 models get 5,000 free searches a month and then cost money; see Google's Gemini API pricing page. The other features cost only normal generation tokens.
- The AI can be wrong; for anything safety-critical (gas, electrical, warranty), follow the official manual.

---

*Built as a single self-contained `index.html` — no build step, no dependencies to install.*
