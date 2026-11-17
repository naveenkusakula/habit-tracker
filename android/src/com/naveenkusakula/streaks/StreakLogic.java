package com.naveenkusakula.streaks;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * All habit rules for Streaks, with no Android dependencies, so the exact
 * code that ships in the app also runs in the plain-JVM test suite
 * (test/StreakLogicTest.java). Every method takes "today" as a parameter
 * so tests are deterministic.
 *
 * Rules (identical in the web version):
 *  - Do it: the streak is the run of consecutive done days ending today,
 *    or ending yesterday if today is not done yet (the day isn't over).
 *  - Don't do: the streak is the whole days since the later of the habit's
 *    creation day and its last break; a break today means 0.
 *  - Stored data is never trusted: sanitize() validates and rebuilds it.
 */
public final class StreakLogic {

    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    public static final int MAX_NAME = 60;

    public static final String[] CATEGORY_IDS = {
        "health", "fitness", "mind", "work", "home", "money", "other"
    };

    private StreakLogic() {}

    public static class HabitData {
        public String id;
        public String name;
        public String type;      /* "do" or "avoid" */
        public String category;
        public String createdAt; /* ISO date */
        public Set<String> completions = new HashSet<>();
        public List<String> breaks = new ArrayList<>();
    }

    /* ---------------- dates ---------------- */

    public static String fmt(LocalDate d) { return d.format(ISO); }

    public static boolean isDateStr(String s) {
        if (s == null || s.length() != 10) return false;
        try {
            LocalDate d = LocalDate.parse(s, ISO);
            return fmt(d).equals(s);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /* ---------------- streaks ---------------- */

    public static int doStreak(Set<String> completions, LocalDate today) {
        LocalDate cursor = today;
        if (!completions.contains(fmt(cursor))) cursor = cursor.minusDays(1);
        int streak = 0;
        while (completions.contains(fmt(cursor))) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    public static int avoidStreak(String createdAt, List<String> breaks, LocalDate today) {
        String todayS = fmt(today);
        if (breaks.contains(todayS)) return 0;
        String anchor = createdAt;
        for (String b : breaks) if (b.compareTo(anchor) > 0) anchor = b;
        long days = today.toEpochDay() - LocalDate.parse(anchor, ISO).toEpochDay();
        return (int) Math.max(0, days);
    }

    /* ---------------- today summary ---------------- */

    public static int percent(int done, int total) {
        if (total <= 0) return 0;
        return (int) Math.round(100.0 * done / total);
    }

    /* The "condition" line under the hero number, weather-style. */
    public static String conditionText(int done, int total) {
        if (total <= 0) return "No habits yet";
        if (done == 0) return "Nothing yet";
        if (done == total) return "All clear";
        int pct = percent(done, total);
        if (pct >= 60) return "Mostly done";
        if (pct >= 30) return "Partly done";
        return "Just starting";
    }

    /* Fraction of do-habits completed on a given day (0..1). Habits created
       after that day don't count toward it. 0 when nothing was tracked. */
    public static double dayFraction(List<HabitData> doHabits, LocalDate day) {
        String ds = fmt(day);
        int total = 0, done = 0;
        for (HabitData h : doHabits) {
            if (h.createdAt.compareTo(ds) > 0) continue;
            total++;
            if (h.completions.contains(ds)) done++;
        }
        return total == 0 ? 0.0 : (double) done / total;
    }

    /* How many do-habits were being tracked on a given day (created on or
       before it). Lets the UI tell "nothing tracked" apart from "0% done". */
    public static int trackedCount(List<HabitData> doHabits, LocalDate day) {
        String ds = fmt(day);
        int total = 0;
        for (HabitData h : doHabits) if (h.createdAt.compareTo(ds) <= 0) total++;
        return total;
    }

    /* Per-day states for one habit over the 7 days ending today.
       Index 0 = six days ago, index 6 = today.
       Do:    1 = done, 0 = not done.
       Avoid: 2 = broken, 1 = clean full day, 0 = today pending or before creation. */
    public static int[] weekStates(HabitData h, LocalDate today) {
        int[] out = new int[7];
        for (int i = 0; i < 7; i++) {
            LocalDate d = today.minusDays(6 - i);
            String ds = fmt(d);
            if (h.type.equals("do")) {
                out[i] = h.completions.contains(ds) ? 1 : 0;
            } else {
                if (h.breaks.contains(ds)) out[i] = 2;
                else if (i < 6 && ds.compareTo(h.createdAt) >= 0) out[i] = 1;
                else out[i] = 0;
            }
        }
        return out;
    }

    /* ---------------- sanitize ---------------- */

    /**
     * Validates raw stored fields and rebuilds a clean habit, or returns
     * null when the entry is unusable. Future-dated completions and breaks
     * are dropped (they cannot have happened yet).
     */
    public static HabitData sanitize(String id, String name, String type, String category,
                                     String createdAt, Set<String> rawCompletions,
                                     List<String> rawBreaks, LocalDate today) {
        if (type == null || (!type.equals("do") && !type.equals("avoid"))) return null;
        if (name == null) return null;
        String cleanName = name.trim();
        if (cleanName.isEmpty()) return null;
        if (cleanName.length() > MAX_NAME) cleanName = cleanName.substring(0, MAX_NAME);

        HabitData h = new HabitData();
        h.id = (id == null || id.isEmpty()) ? UUID.randomUUID().toString() : id;
        h.name = cleanName;
        h.type = type;
        h.category = "other";
        if (category != null) {
            for (String c : CATEGORY_IDS) if (c.equals(category)) h.category = c;
        }
        String todayS = fmt(today);
        h.createdAt = isDateStr(createdAt) ? createdAt : todayS;

        if (rawCompletions != null) {
            for (String k : rawCompletions) {
                if (isDateStr(k) && k.compareTo(todayS) <= 0) h.completions.add(k);
            }
        }
        if (rawBreaks != null) {
            for (String b : rawBreaks) {
                if (isDateStr(b) && b.compareTo(todayS) <= 0 && !h.breaks.contains(b)) {
                    h.breaks.add(b);
                }
            }
            Collections.sort(h.breaks);
        }
        return h;
    }
}
