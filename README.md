# Streaks — Daily Habit Tracker

A simple daily habit tracker in the Gradient Weather style: a living gradient
background, frosted-glass cards, big light numerals and pill chips. Free, no
account, no server — it runs entirely in your browser and works offline.

## The idea

Only two kinds of habits, nothing timed:

- **Do it** — tap the round check button when you've done it today.
  The streak counts your consecutive done days. Miss a day and it resets.
- **Don't do it** — every clean day grows the streak on its own.
  The day you slip, tap **Broke it** and the streak goes back to zero
  (there's an Undo if you tapped by mistake).

Each habit gets an obvious category — Health, Fitness, Mind, Work, Home,
Money or Other — and a 7-day dot strip so you can see the week at a glance.

## Run it

- **Simplest:** download this repo and open `index.html` in any browser.
- **On your phone / as a link:** enable GitHub Pages — repo **Settings →
  Pages → Deploy from a branch → `main` / root** — and GitHub gives you a
  free `https://<username>.github.io/habit-tracker/` address. Add it to your
  home screen and it behaves like an app.
- Or drop the three files (`index.html`, `styles.css`, `app.js`) onto any
  free static host (Netlify, Vercel, Cloudflare Pages).

## Your data

Habits are stored in your browser's `localStorage` on the device you use —
nothing is uploaded anywhere. That also means data doesn't sync between
devices, and clearing the browser's site data clears your habits.

## Design

Modelled on the Gradient Weather apps: one full-screen sky gradient, a
giant hairline hero number (today's % done) with a weather-style
"condition" line ("Mostly done", "All clear"), a This Week strip like an
hourly forecast, and habit rows floating on the gradient with hairline
dividers. No cards. When every do-habit is done, the sky cross-fades to a
warm "clear day" gradient. The web version above and the Android app share
this design and the same habit rules.

## Android app

`android/` contains a native Android version, built for privacy and ready
for the Play Store:

- **No permissions at all** — the manifest declares none, so Android itself
  blocks the app from any network access. Nothing can be sent anywhere.
- **Zero third-party dependencies** — Android framework only, no WebView.
- Data lives in app-private storage; backups and device transfer are
  disabled in the manifest.
- **Tested** — all habit rules live in `StreakLogic.java` (pure Java) with
  a dependency-free test suite in `android/test/` (74 checks: streaks,
  leap years, month/year boundaries, stored-data validation, week math).
- Build everything with `android/build.sh` (JDK 17 + Android SDK
  build-tools + bundletool, no Gradle): it runs the tests, then produces a
  signed APK and a signed Play Store bundle (`.aab`). The signing keystore
  is **not** in this repo — it stays local, because whoever holds it can
  sign updates as this app.
- Privacy policy: [PRIVACY.md](PRIVACY.md) — the app collects no data.

## Tech

Plain HTML, CSS and JavaScript. No build step, no dependencies, no tracking.
