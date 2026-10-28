# Installing Apexview Fork (Jarett's version)

This is the plain-English version. You do not need to know Git, Android
Studio, or anything about how the app is built. You only need your phone,
your F1 TV login, and a few minutes.

## What "fork" means here

This repository is Jarett's own copy of the Apexview app. It is called
**Apexview Fork** on your phone, and it installs *next to* the original
Apexview — it does not replace or delete it. You will see two icons.
Use the one that says "Apexview Fork" for the new features.

Because it is a separate app, it starts fresh: you will sign in to F1 TV
once in the fork, and any layouts you arranged in the original do not
carry over automatically.

## The easy way: download the built app from GitHub

Every time a change is pushed to this repository, GitHub builds the app
for you. No computer setup is needed.

1. Open https://github.com/jarettp/apexview in your phone's browser and
   make sure you are signed in to GitHub.
2. Tap the **Actions** tab at the top.
3. Tap the newest **Build APK** run at the top of the list.
   - A green check means the build is done and ready.
   - A yellow dot means it is still building — wait a few minutes and
     refresh the page.
4. Scroll to the bottom of that run, to **Artifacts**, and tap
   **apexview-fork-debug**. A zip file downloads.
5. Open the zip: in the Files app, go to Downloads, tap the zip, and tap
   **Extract**. Then tap the `.apk` file inside it.
6. Your phone may say the browser or Files is not allowed to install
   unknown apps. That is normal for an app that did not come from the
   Play Store. Tap **Settings** in that prompt, turn the allow switch
   on, go back, and tap **Install**.
7. Open **Apexview Fork** and sign in with your F1 TV account.

That is it. To try the new features:

- **Forced laptop mode:** while watching, tap the grid icon to open
  Layout, and turn on **Force laptop mode**. The stream goes on the
  top half and the timing tower and track map on the bottom half, even
  though the Fold 8 does not report a half-open hinge. Turn it off to
  go back to the normal layout.
- **Scrollable settings sheets:** the Layout and Map sheets now scroll,
  so their lower controls are reachable on a short screen.

## Updating later

Repeat steps 1–6 with the newest Build APK run. It should install over
the old fork. If Android ever refuses with a signature or "app not
installed" message, uninstall Apexview Fork first and then install the
new one — you will just need to sign in to F1 TV again. That is a
quirk of these test builds, not a problem with your phone.

## The other way: Android Studio (only if you want it)

If you ever want to build on your own computer instead, the short
version is: install Android Studio, open this repository in it, plug
your phone in with a USB cable, turn on Developer options > USB
debugging on the phone, and press Run in Android Studio. You do not
need this route to install the app — the GitHub build above is easier.

## If something looks wrong

- Two Apexview icons is expected: "Apexview" is the original,
  "Apexview Fork" is this one.
- If the fork asks you to sign in again, that is expected too — it is
  a separate app with its own saved login.
- If a download is a zip, remember to extract it first — the `.apk`
  inside is the actual app file, not the zip itself.
