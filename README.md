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

## Tech

Plain HTML, CSS and JavaScript. No build step, no dependencies, no tracking.
