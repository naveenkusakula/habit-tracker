/* Streaks — a tiny daily habit tracker.
   Two kinds of habits:
   - "do":    mark it done each day; the streak counts consecutive done days.
   - "avoid": every clean day grows the streak; tap "Broke it" and it resets.
   No timers, no accounts — everything is stored in this browser.
   The rules here mirror StreakLogic.java in the Android app exactly;
   that class carries the unit tests for both. */

"use strict";

const LS_KEY = "streaks.habits.v1";

const CATEGORIES = [
  { id: "health",  label: "Health",  color: "#34d399" },
  { id: "fitness", label: "Fitness", color: "#fb923c" },
  { id: "mind",    label: "Mind",    color: "#a78bfa" },
  { id: "work",    label: "Work",    color: "#38bdf8" },
  { id: "home",    label: "Home",    color: "#facc15" },
  { id: "money",   label: "Money",   color: "#4ade80" },
  { id: "other",   label: "Other",   color: "#cbd5e1" },
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
function daysBetween(a, b) { return Math.round((parse(b) - parse(a)) / DAY); }
function prettyDate(s) {
  return parse(s).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/* ---------- state ---------- */
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
function isDateStr(s) {
  if (typeof s !== "string" || !DATE_RE.test(s)) return false;
  const d = parse(s);
  return !isNaN(d.getTime()) && fmt(d) === s;
}
/* Stored data is never trusted: every field is validated and rebuilt, so a
   corrupted or tampered entry is dropped or repaired instead of breaking
   the app or smuggling unexpected values into the page. */
function sanitizeHabit(raw) {
  if (!raw || typeof raw !== "object") return null;
  if (raw.type !== "do" && raw.type !== "avoid") return null;
  if (typeof raw.name !== "string" || !raw.name.trim()) return null;
  const today = todayStr();
  const completions = {};
  if (raw.completions && typeof raw.completions === "object" && !Array.isArray(raw.completions)) {
    for (const k of Object.keys(raw.completions)) {
      if (isDateStr(k) && k <= today && raw.completions[k] === true) completions[k] = true;
    }
  }
  const breaks = Array.isArray(raw.breaks)
    ? [...new Set(raw.breaks.filter((b) => isDateStr(b) && b <= today))].sort()
    : [];
  return {
    id: typeof raw.id === "string" && raw.id ? raw.id : uid(),
    name: raw.name.trim().slice(0, 60),
    type: raw.type,
    category: CATEGORIES.some((c) => c.id === raw.category) ? raw.category : "other",
    createdAt: isDateStr(raw.createdAt) ? raw.createdAt : today,
    completions,
    breaks,
  };
}
let state = load();
function load() {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (raw) {
      const data = JSON.parse(raw);
      if (data && Array.isArray(data.habits)) {
        return { habits: data.habits.map(sanitizeHabit).filter(Boolean) };
      }
    }
  } catch (e) { /* corrupted storage falls through to a fresh start */ }
  return { habits: [] };
}
function save() {
  try { localStorage.setItem(LS_KEY, JSON.stringify(state)); } catch (e) { /* storage full/blocked */ }
}
function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

/* ---------- streak math (mirrors StreakLogic.java) ---------- */
function doStreak(h) {
  let cursor = new Date();
  if (!h.completions[fmt(cursor)]) cursor.setDate(cursor.getDate() - 1);
  let streak = 0;
  while (h.completions[fmt(cursor)]) {
    streak++;
    cursor.setDate(cursor.getDate() - 1);
  }
  return streak;
}
function avoidStreak(h) {
  const today = todayStr();
  if (h.breaks.includes(today)) return 0;
  let anchor = h.createdAt;
  for (const b of h.breaks) if (b > anchor) anchor = b;
  return Math.max(0, daysBetween(anchor, today));
}
function percent(done, total) {
  return total <= 0 ? 0 : Math.round((100 * done) / total);
}
function conditionText(done, total) {
  if (total <= 0) return "No habits yet";
  if (done === 0) return "Nothing yet";
  if (done === total) return "All clear";
  const pct = percent(done, total);
  if (pct >= 60) return "Mostly done";
  if (pct >= 30) return "Partly done";
  return "Just starting";
}
function trackedCount(dos, ds) {
  return dos.filter((h) => h.createdAt <= ds).length;
}
function dayFraction(dos, ds) {
  const tracked = dos.filter((h) => h.createdAt <= ds);
  if (!tracked.length) return 0;
  return tracked.filter((h) => h.completions[ds]).length / tracked.length;
}

/* ---------- dom ---------- */
const $ = (id) => document.getElementById(id);
const els = {
  bgClear: $("bgClear"), dateline: $("dateline"),
  heroNum: $("heroNum"), heroPct: $("heroPct"),
  cond: $("condText"), sub: $("subLine"),
  weekSection: $("weekSection"), weekStrip: $("weekStrip"),
  doList: $("doList"), avoidList: $("avoidList"),
  doEmpty: $("doEmpty"), avoidEmpty: $("avoidEmpty"),
  fab: $("fab"), scrim: $("scrim"), sheet: $("sheet"),
  name: $("nameInput"), typeDo: $("typeDo"), typeAvoid: $("typeAvoid"),
  catList: $("catList"), cancel: $("cancelBtn"), add: $("addBtn"),
};

function escapeHTML(s) {
  return s.replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

/* ---------- rendering ---------- */
let armedBreak = null;   /* habit id whose "Broke it" is waiting for a confirming tap */
let armedTimer = null;

function render() {
  const today = todayStr();
  const now = new Date();
  const dos = state.habits.filter((h) => h.type === "do");
  const avoids = state.habits.filter((h) => h.type === "avoid");
  const done = dos.filter((h) => h.completions[today]).length;
  const total = dos.length;
  const allClear = total > 0 && done === total;
  let best = 0;
  for (const h of state.habits) {
    best = Math.max(best, h.type === "do" ? doStreak(h) : avoidStreak(h));
  }

  els.dateline.textContent = now.toLocaleDateString(undefined, {
    weekday: "long", month: "long", day: "numeric",
  });

  /* hero */
  els.heroNum.textContent = total === 0 ? "–" : String(percent(done, total));
  els.heroPct.textContent = total === 0 ? "" : "%";
  els.cond.textContent = conditionText(done, total);
  els.sub.textContent = total === 0
    ? "Add a habit to start your day"
    : `${done} of ${total} done today` + (best > 0 ? `  ·  🔥 ${best}-day streak` : "");
  els.bgClear.classList.toggle("on", allClear);

  /* this week */
  els.weekSection.hidden = dos.length === 0;
  if (dos.length) {
    const letters = ["S", "M", "T", "W", "T", "F", "S"];
    let html = "";
    for (let i = 6; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
      const ds = fmt(d);
      const tracked = trackedCount(dos, ds);
      const frac = dayFraction(dos, ds);
      const alpha = tracked === 0 ? 0 : 0.27 + frac * 0.73;
      html += `<div class="wcell${i === 0 ? " today" : ""}">
        <div class="wletter">${letters[d.getDay()]}</div>
        <div class="wdot" style="background:rgba(255,255,255,${alpha.toFixed(2)});border-color:rgba(255,255,255,${tracked === 0 ? 0.3 : 0})"></div>
        <div class="wpct">${tracked === 0 ? "–" : Math.round(frac * 100) + "%"}</div>
      </div>`;
    }
    els.weekStrip.innerHTML = html;
  }

  /* lists */
  els.doList.innerHTML = dos.map(doRowHTML).join("");
  els.avoidList.innerHTML = avoids.map(avoidRowHTML).join("");
  els.doEmpty.style.display = dos.length ? "none" : "";
  els.avoidEmpty.style.display = avoids.length ? "none" : "";
}

function doRowHTML(h) {
  const today = todayStr();
  const done = !!h.completions[today];
  const streak = doStreak(h);
  const sub = streak > 0
    ? `🔥 ${streak} ${streak === 1 ? "day" : "days"}`
    : done ? "Done today" : "Not done yet";
  return `<div class="hrow do" data-id="${h.id}" role="button" aria-pressed="${done}"
      aria-label="${escapeHTML(h.name)} — tap to ${done ? "undo" : "mark done"}; long-press to delete">
    <div class="hmain">
      <div class="hname">${escapeHTML(h.name)}</div>
      <div class="hsub">${catById(h.category).label} &middot; ${sub}</div>
    </div>
    <button class="check${done ? " done" : ""}" data-check="${h.id}" aria-hidden="true" tabindex="-1">${done ? "✓" : ""}</button>
  </div>`;
}

function avoidRowHTML(h) {
  const today = todayStr();
  const broke = h.breaks.includes(today);
  const streak = avoidStreak(h);
  const armed = armedBreak === h.id;
  const action = broke
    ? `<button class="undo-link" data-action="undo" data-id="${h.id}">Undo</button>`
    : `<button class="broke-link${armed ? " armed" : ""}" data-action="break" data-id="${h.id}">${armed ? "Tap again — reset to 0" : "Broke it"}</button>`;
  const sub = broke
    ? `${catById(h.category).label} &middot; Broken today — back to 0`
    : `${catById(h.category).label} &middot; since ${prettyDate(h.createdAt)}`;
  return `<div class="hrow" data-id="${h.id}">
    <div class="hmain">
      <div class="hname">${escapeHTML(h.name)}</div>
      <div class="hsub">${sub}</div>
      ${action}
    </div>
    <div>
      <div class="anum${broke ? " broken" : ""}">${streak}</div>
      <div class="adays">${streak === 1 ? "DAY" : "DAYS"}</div>
    </div>
  </div>`;
}

/* ---------- interactions ---------- */
function findHabit(id) { return state.habits.find((h) => h.id === id); }

function toggleDo(id) {
  const h = findHabit(id);
  if (!h) return;
  const today = todayStr();
  if (h.completions[today]) delete h.completions[today];
  else h.completions[today] = true;
  save();
  render();
}

function armBreak(id) {
  armedBreak = id;
  render();
  clearTimeout(armedTimer);
  armedTimer = setTimeout(() => { armedBreak = null; render(); }, 2600);
}
function doBreak(id) {
  const h = findHabit(id);
  if (!h) return;
  const today = todayStr();
  if (!h.breaks.includes(today)) {
    h.breaks.push(today);
    h.breaks.sort();
  }
  armedBreak = null;
  save();
  render();
}
function undoBreak(id) {
  const h = findHabit(id);
  if (!h) return;
  h.breaks = h.breaks.filter((b) => b !== todayStr());
  save();
  render();
}
function deleteHabit(id) {
  state.habits = state.habits.filter((h) => h.id !== id);
  if (armedBreak === id) armedBreak = null;
  save();
  render();
}

/* Row clicks (do-rows toggle; buttons inside avoid-rows act). A long-press
   on any row offers delete, like the Android app, and swallows the click
   that follows it. */
let longPressed = false;
let pressTimer = null;

function bindRowEvents(listEl) {
  listEl.addEventListener("pointerdown", (e) => {
    const row = e.target.closest(".hrow");
    if (!row || e.target.closest("button")) return;
    longPressed = false;
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => {
      longPressed = true;
      const h = findHabit(row.dataset.id);
      if (h && confirm(`Delete "${h.name}"? Its streak history will be gone.`)) deleteHabit(h.id);
    }, 550);
  });
  for (const ev of ["pointerup", "pointerleave", "pointercancel"]) {
    listEl.addEventListener(ev, () => clearTimeout(pressTimer));
  }
  listEl.addEventListener("click", (e) => {
    if (longPressed) { longPressed = false; return; }
    const actionBtn = e.target.closest("[data-action]");
    if (actionBtn) {
      const { action, id } = actionBtn.dataset;
      if (action === "break") (armedBreak === id ? doBreak : armBreak)(id);
      else if (action === "undo") undoBreak(id);
      return;
    }
    const row = e.target.closest(".hrow.do");
    if (row) toggleDo(row.dataset.id);
  });
  listEl.addEventListener("contextmenu", (e) => {
    if (e.target.closest(".hrow")) e.preventDefault();
  });
}
bindRowEvents(els.doList);
bindRowEvents(els.avoidList);

/* ---------- add-habit sheet ---------- */
let formType = "do";
let formCategory = "health";

function buildCatList() {
  els.catList.innerHTML = CATEGORIES.map((c) => `
    <button type="button" class="cat-row${c.id === formCategory ? " sel" : ""}" data-cat="${c.id}">
      <span class="cdot" style="--c:${c.color}"></span>
      <span class="clabel">${c.label}</span>
      <span class="cmark">✓</span>
    </button>`).join("");
}
function setFormType(t) {
  formType = t;
  els.typeDo.classList.toggle("is-active", t === "do");
  els.typeAvoid.classList.toggle("is-active", t === "avoid");
}
function openSheet() {
  els.scrim.hidden = false;
  els.sheet.hidden = false;
  els.name.value = "";
  setFormType("do");
  formCategory = "health";
  buildCatList();
  setTimeout(() => els.name.focus(), 60);
}
function closeSheet() {
  els.scrim.hidden = true;
  els.sheet.hidden = true;
}
els.fab.addEventListener("click", openSheet);
els.cancel.addEventListener("click", closeSheet);
els.scrim.addEventListener("click", closeSheet);
els.typeDo.addEventListener("click", () => setFormType("do"));
els.typeAvoid.addEventListener("click", () => setFormType("avoid"));
els.catList.addEventListener("click", (e) => {
  const row = e.target.closest("[data-cat]");
  if (!row) return;
  formCategory = row.dataset.cat;
  buildCatList();
});
function submitForm() {
  const name = els.name.value.trim();
  if (!name) { els.name.focus(); return; }
  const h = sanitizeHabit({
    id: uid(), name, type: formType, category: formCategory,
    createdAt: todayStr(), completions: {}, breaks: [],
  });
  if (!h) return;
  state.habits.push(h);
  save();
  closeSheet();
  render();
}
els.add.addEventListener("click", submitForm);
els.name.addEventListener("keydown", (e) => { if (e.key === "Enter") submitForm(); });
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !els.sheet.hidden) closeSheet();
});

/* Re-render when the day rolls over while the page is open. */
let renderedDay = todayStr();
setInterval(() => {
  const t = todayStr();
  if (t !== renderedDay) { renderedDay = t; render(); }
}, 30000);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) {
    const t = todayStr();
    if (t !== renderedDay) { renderedDay = t; render(); }
  }
});

render();
