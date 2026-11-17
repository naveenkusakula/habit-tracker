/* Streaks — a tiny daily habit tracker.
   Two kinds of habits:
   - "do":    mark it done each day; the streak counts consecutive done days.
   - "avoid": every clean day grows the streak; tap "Broke it" and it resets.
   No timers, no accounts — everything is stored in this browser. */

"use strict";

const LS_KEY = "streaks.habits.v1";

const CATEGORIES = [
  { id: "health",  label: "Health",  color: "#34d399" },
  { id: "fitness", label: "Fitness", color: "#fb923c" },
  { id: "mind",    label: "Mind",    color: "#a78bfa" },
  { id: "work",    label: "Work",    color: "#60a5fa" },
  { id: "home",    label: "Home",    color: "#fbbf24" },
  { id: "money",   label: "Money",   color: "#2dd4bf" },
  { id: "other",   label: "Other",   color: "#e2e8f0" },
];
const catById = (id) => CATEGORIES.find((c) => c.id === id) || CATEGORIES[CATEGORIES.length - 1];

/* ---------- dates (local, YYYY-MM-DD) ---------- */
const DAY = 86400000;
function fmt(d) {
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function parse(s) {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(y, m - 1, d);
}
function todayStr() { return fmt(new Date()); }
function addDays(s, n) { return fmt(new Date(parse(s).getTime() + n * DAY)); }
function diffDays(a, b) { return Math.round((parse(a) - parse(b)) / DAY); }

/* ---------- state ---------- */
let state = load();
function load() {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (raw) {
      const data = JSON.parse(raw);
      if (data && Array.isArray(data.habits)) return data;
    }
  } catch (e) { /* corrupted storage falls through to a fresh start */ }
  return { habits: [] };
}
function save() {
  localStorage.setItem(LS_KEY, JSON.stringify(state));
}
function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

/* ---------- streak math ---------- */
function doStreak(h) {
  let streak = 0;
  let cursor = todayStr();
  if (!h.completions[cursor]) cursor = addDays(cursor, -1); // today still open
  while (h.completions[cursor]) {
    streak++;
    cursor = addDays(cursor, -1);
  }
  return streak;
}
function lastBreak(h) {
  return h.breaks.length ? h.breaks[h.breaks.length - 1] : null;
}
function avoidStreak(h) {
  const base = lastBreak(h) || h.createdAt;
  return Math.max(0, diffDays(todayStr(), base));
}
function brokenToday(h) {
  return lastBreak(h) === todayStr();
}

/* ---------- rendering ---------- */
const $ = (id) => document.getElementById(id);
const doCards = $("doCards"), avoidCards = $("avoidCards");

function weekDots(h) {
  const t = todayStr();
  const letters = ["S", "M", "T", "W", "T", "F", "S"];
  let html = '<div class="week">';
  for (let i = 6; i >= 0; i--) {
    const day = addDays(t, -i);
    const dow = parse(day).getDay();
    let cls = "wd";
    if (day < h.createdAt) cls += " pre";
    else if (h.type === "do") { if (h.completions[day]) cls += " full-do"; }
    else { if (h.breaks.includes(day)) cls += " broken"; else cls += " full-avoid"; }
    if (i === 0) cls += " today";
    html += `<span class="wday"><span class="${cls}"></span><span class="wl">${letters[dow]}</span></span>`;
  }
  return html + "</div>";
}

function cardHTML(h) {
  const cat = catById(h.category);
  const del = `<button class="del-btn" data-action="delete" data-id="${h.id}" aria-label="Delete habit">×</button>`;
  const head = `
    <div class="card-top">
      <span class="cat-chip"><span class="dot" style="background:${cat.color}"></span>${cat.label}</span>
    </div>
    <div class="card-name">${escapeHTML(h.name)}</div>`;

  if (h.type === "do") {
    const done = !!h.completions[todayStr()];
    const streak = doStreak(h);
    return `
    <article class="card glass" style="--accent:${cat.color}">
      ${del}
      <div class="card-main">
        ${head}
        <div class="streak-line"><span class="flame">🔥</span><span class="streak-num">${streak}</span><span class="streak-word">day streak</span></div>
        ${weekDots(h)}
      </div>
      <button class="check-btn ${done ? "done" : ""}" data-action="toggle-do" data-id="${h.id}" aria-label="${done ? "Undo done" : "Mark done"}">
        <svg viewBox="0 0 24 24" fill="none"><path d="M4 12.5l5 5L20 6.5" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
    </article>`;
  }

  const broken = brokenToday(h);
  const streak = avoidStreak(h);
  return `
  <article class="card glass ${broken ? "is-broken" : ""}" style="--accent:${cat.color}">
    ${del}
    <div class="card-main">
      ${head}
      ${broken
        ? `<p class="broken-note">Streak broken today — it starts again tomorrow.</p>`
        : `<div class="streak-line"><span class="flame">🔥</span><span class="streak-num">${streak}</span><span class="streak-word">${streak === 1 ? "day clean" : "days clean"}</span></div>`}
      ${weekDots(h)}
    </div>
    <div class="avoid-actions">
      ${broken
        ? `<button class="undo-btn" data-action="undo-break" data-id="${h.id}">Undo</button>`
        : `<button class="broke-btn" data-action="break" data-id="${h.id}">Broke it</button>`}
    </div>
  </article>`;
}

function escapeHTML(s) {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function render() {
  const dos = state.habits.filter((h) => h.type === "do");
  const avoids = state.habits.filter((h) => h.type === "avoid");

  doCards.innerHTML = dos.map(cardHTML).join("");
  avoidCards.innerHTML = avoids.map(cardHTML).join("");
  $("doEmpty").style.display = dos.length ? "none" : "block";
  $("avoidEmpty").style.display = avoids.length ? "none" : "block";
  $("doPill").textContent = dos.length;
  $("avoidPill").textContent = avoids.length;

  const t = new Date();
  $("dateline").textContent = t.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: "numeric" });

  const doneToday = dos.filter((h) => h.completions[todayStr()]).length;
  $("doneCount").textContent = `${doneToday}/${dos.length}`;
  $("barFill").style.width = dos.length ? `${(doneToday / dos.length) * 100}%` : "0%";

  const clean = avoids.filter((h) => !brokenToday(h)).length;
  const parts = [];
  if (dos.length) parts.push(doneToday === dos.length ? "All do-habits done. 🎉" : `${dos.length - doneToday} do-habit${dos.length - doneToday === 1 ? "" : "s"} left today.`);
  if (avoids.length) parts.push(clean === avoids.length ? "Every clean streak is alive." : `${avoids.length - clean} streak broken today.`);
  $("summarySub").textContent = parts.length ? parts.join(" ") : "Add your first habit to get started.";
}

/* ---------- actions ---------- */
function findHabit(id) { return state.habits.find((h) => h.id === id); }

document.addEventListener("click", (e) => {
  const btn = e.target.closest("[data-action]");
  if (!btn) return;
  const h = findHabit(btn.dataset.id);
  if (!h) return;
  const action = btn.dataset.action;

  if (action === "toggle-do") {
    const t = todayStr();
    if (h.completions[t]) delete h.completions[t];
    else h.completions[t] = true;
    save(); render();
  }

  if (action === "break") {
    if (!btn.classList.contains("armed")) {
      btn.classList.add("armed");
      btn.textContent = "Tap again to confirm";
      setTimeout(() => render(), 2600);
      return;
    }
    const t = todayStr();
    if (!h.breaks.includes(t)) h.breaks.push(t);
    h.breaks.sort();
    save(); render();
  }

  if (action === "undo-break") {
    h.breaks = h.breaks.filter((d) => d !== todayStr());
    save(); render();
  }

  if (action === "delete") {
    if (!btn.classList.contains("armed")) {
      btn.classList.add("armed");
      btn.textContent = "Delete?";
      setTimeout(() => render(), 2600);
      return;
    }
    state.habits = state.habits.filter((x) => x.id !== h.id);
    save(); render();
  }
});

/* ---------- add-habit sheet ---------- */
const backdrop = $("backdrop");
const nameInput = $("habitName");
const saveBtn = $("saveBtn");
let newType = "do";
let newCategory = "other";

const chipsBox = $("categoryChips");
chipsBox.innerHTML = CATEGORIES.map((c) =>
  `<button type="button" class="chip ${c.id === newCategory ? "active" : ""}" data-cat="${c.id}"><span class="dot" style="background:${c.color}"></span>${c.label}</button>`
).join("");
chipsBox.addEventListener("click", (e) => {
  const chip = e.target.closest("[data-cat]");
  if (!chip) return;
  newCategory = chip.dataset.cat;
  chipsBox.querySelectorAll(".chip").forEach((el) => el.classList.toggle("active", el.dataset.cat === newCategory));
});

$("typeToggle").addEventListener("click", (e) => {
  const seg = e.target.closest("[data-type]");
  if (!seg) return;
  newType = seg.dataset.type;
  document.querySelectorAll(".seg").forEach((el) => el.classList.toggle("active", el.dataset.type === newType));
});

function syncSave() { saveBtn.disabled = !nameInput.value.trim(); }
nameInput.addEventListener("input", syncSave);
nameInput.addEventListener("keydown", (e) => { if (e.key === "Enter" && nameInput.value.trim()) saveHabit(); });

function openSheet() {
  backdrop.hidden = false;
  syncSave();
  setTimeout(() => nameInput.focus(), 60);
}
function closeSheet() {
  backdrop.hidden = true;
  nameInput.value = "";
}
function saveHabit() {
  const name = nameInput.value.trim();
  if (!name) return;
  state.habits.push({
    id: uid(),
    name,
    type: newType,
    category: newCategory,
    createdAt: todayStr(),
    completions: {},
    breaks: [],
  });
  save();
  closeSheet();
  render();
}

$("addBtn").addEventListener("click", openSheet);
$("cancelBtn").addEventListener("click", closeSheet);
saveBtn.addEventListener("click", saveHabit);
backdrop.addEventListener("click", (e) => { if (e.target === backdrop) closeSheet(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !backdrop.hidden) closeSheet(); });

/* Re-render when the day rolls over while the app is open. */
let currentDay = todayStr();
setInterval(() => {
  if (todayStr() !== currentDay) {
    currentDay = todayStr();
    render();
  }
}, 30000);

render();
