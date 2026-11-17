package com.naveenkusakula.streaks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dependency-free test suite for StreakLogic — the same class the app
 * ships. Run with:
 *   javac -encoding UTF-8 -d /tmp/streak-test \
 *     src/com/naveenkusakula/streaks/StreakLogic.java test/StreakLogicTest.java
 *   java -cp /tmp/streak-test com.naveenkusakula.streaks.StreakLogicTest
 * Exits 0 only when every check passes.
 */
public class StreakLogicTest {

    private static int passed = 0, failed = 0;

    private static void check(String name, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) { passed++; }
        else {
            failed++;
            System.out.println("FAIL: " + name + " — expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void checkDouble(String name, double actual, double expected) {
        if (Math.abs(actual - expected) < 1e-9) { passed++; }
        else {
            failed++;
            System.out.println("FAIL: " + name + " — expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void checkWeek(String name, int[] actual, int... expected) {
        check(name, Arrays.toString(actual), Arrays.toString(expected));
    }

    private static Set<String> set(String... dates) { return new HashSet<>(Arrays.asList(dates)); }
    private static List<String> list(String... dates) { return new ArrayList<>(Arrays.asList(dates)); }
    private static LocalDate d(String iso) { return LocalDate.parse(iso); }

    private static StreakLogic.HabitData habit(String type, String createdAt,
                                               Set<String> completions, List<String> breaks) {
        StreakLogic.HabitData h = new StreakLogic.HabitData();
        h.id = "t"; h.name = "Test"; h.type = type; h.category = "health";
        h.createdAt = createdAt;
        if (completions != null) h.completions = completions;
        if (breaks != null) h.breaks = breaks;
        return h;
    }

    public static void main(String[] args) {
        LocalDate today = d("2026-11-17"); /* a Tuesday */

        /* ---------- isDateStr ---------- */
        check("date: ordinary valid", StreakLogic.isDateStr("2026-11-17"), true);
        check("date: leap day in leap year", StreakLogic.isDateStr("2024-02-29"), true);
        check("date: leap day in non-leap year", StreakLogic.isDateStr("2026-02-29"), false);
        check("date: Feb 30 never exists", StreakLogic.isDateStr("2026-02-30"), false);
        check("date: month 13", StreakLogic.isDateStr("2026-13-01"), false);
        check("date: day 00", StreakLogic.isDateStr("2026-11-00"), false);
        check("date: wrong order", StreakLogic.isDateStr("17-11-2026"), false);
        check("date: unpadded", StreakLogic.isDateStr("2026-1-5"), false);
        check("date: null", StreakLogic.isDateStr(null), false);
        check("date: empty", StreakLogic.isDateStr(""), false);
        check("date: garbage", StreakLogic.isDateStr("not-a-date"), false);

        /* ---------- doStreak ---------- */
        check("do: nothing done", StreakLogic.doStreak(set(), today), 0);
        check("do: today only", StreakLogic.doStreak(set("2026-11-17"), today), 1);
        check("do: today + yesterday", StreakLogic.doStreak(set("2026-11-17", "2026-11-16"), today), 2);
        check("do: yesterday only (today still open)", StreakLogic.doStreak(set("2026-11-16"), today), 1);
        check("do: run ending yesterday", StreakLogic.doStreak(set("2026-11-16", "2026-11-15", "2026-11-14"), today), 3);
        check("do: missed yesterday kills it", StreakLogic.doStreak(set("2026-11-15", "2026-11-14"), today), 0);
        check("do: gap in the middle stops the count",
            StreakLogic.doStreak(set("2026-11-17", "2026-11-16", "2026-11-14", "2026-11-13"), today), 2);
        check("do: full week", StreakLogic.doStreak(set(
            "2026-11-17", "2026-11-16", "2026-11-15", "2026-11-14",
            "2026-11-13", "2026-11-12", "2026-11-11"), today), 7);
        check("do: future completion does not count", StreakLogic.doStreak(set("2026-11-18"), today), 0);
        check("do: month boundary Jan 31 -> Feb 1",
            StreakLogic.doStreak(set("2026-02-01", "2026-01-31", "2026-01-30"), d("2026-02-01")), 3);
        check("do: leap day bridges Feb -> Mar",
            StreakLogic.doStreak(set("2024-03-01", "2024-02-29", "2024-02-28"), d("2024-03-01")), 3);
        check("do: non-leap Feb 28 -> Mar 1",
            StreakLogic.doStreak(set("2026-03-01", "2026-02-28"), d("2026-03-01")), 2);
        check("do: year boundary Dec 31 -> Jan 1",
            StreakLogic.doStreak(set("2027-01-01", "2026-12-31"), d("2027-01-01")), 2);

        /* ---------- avoidStreak ---------- */
        check("avoid: created today", StreakLogic.avoidStreak("2026-11-17", list(), today), 0);
        check("avoid: created yesterday", StreakLogic.avoidStreak("2026-11-16", list(), today), 1);
        check("avoid: created 10 days ago", StreakLogic.avoidStreak("2026-11-07", list(), today), 10);
        check("avoid: broke today", StreakLogic.avoidStreak("2026-11-01", list("2026-11-17"), today), 0);
        check("avoid: broke yesterday", StreakLogic.avoidStreak("2026-11-01", list("2026-11-16"), today), 1);
        check("avoid: broke 3 days ago", StreakLogic.avoidStreak("2026-11-01", list("2026-11-14"), today), 3);
        check("avoid: latest break is the anchor",
            StreakLogic.avoidStreak("2026-10-01", list("2026-10-20", "2026-11-10"), today), 7);
        check("avoid: break before creation is ignored",
            StreakLogic.avoidStreak("2026-11-10", list("2026-11-01"), today), 7);
        check("avoid: broke on creation day, clean since",
            StreakLogic.avoidStreak("2026-11-10", list("2026-11-10"), today), 7);
        check("avoid: across a month boundary",
            StreakLogic.avoidStreak("2026-01-30", list(), d("2026-02-02")), 3);
        check("avoid: across a year boundary",
            StreakLogic.avoidStreak("2026-12-30", list(), d("2027-01-02")), 3);

        /* ---------- percent + conditionText ---------- */
        check("pct: 0 of 0", StreakLogic.percent(0, 0), 0);
        check("pct: 0 of 3", StreakLogic.percent(0, 3), 0);
        check("pct: 1 of 3 rounds to 33", StreakLogic.percent(1, 3), 33);
        check("pct: 2 of 3 rounds to 67", StreakLogic.percent(2, 3), 67);
        check("pct: 3 of 3", StreakLogic.percent(3, 3), 100);
        check("pct: 1 of 2", StreakLogic.percent(1, 2), 50);
        check("cond: no habits", StreakLogic.conditionText(0, 0), "No habits yet");
        check("cond: none done", StreakLogic.conditionText(0, 4), "Nothing yet");
        check("cond: all done", StreakLogic.conditionText(4, 4), "All clear");
        check("cond: 3 of 4 mostly", StreakLogic.conditionText(3, 4), "Mostly done");
        check("cond: 1 of 2 partly", StreakLogic.conditionText(1, 2), "Partly done");
        check("cond: 1 of 6 just starting", StreakLogic.conditionText(1, 6), "Just starting");

        /* ---------- dayFraction ---------- */
        StreakLogic.HabitData a = habit("do", "2026-11-01", set("2026-11-16", "2026-11-17"), null);
        StreakLogic.HabitData b = habit("do", "2026-11-01", set("2026-11-17"), null);
        StreakLogic.HabitData c = habit("do", "2026-11-17", set("2026-11-17"), null); /* created today */
        List<StreakLogic.HabitData> dos = Arrays.asList(a, b, c);
        checkDouble("frac: today 3/3", StreakLogic.dayFraction(dos, d("2026-11-17")), 1.0);
        checkDouble("frac: yesterday 1/2 (third habit not created yet)",
            StreakLogic.dayFraction(dos, d("2026-11-16")), 0.5);
        checkDouble("frac: day with none done", StreakLogic.dayFraction(dos, d("2026-11-15")), 0.0);
        checkDouble("frac: before any habit existed", StreakLogic.dayFraction(dos, d("2026-10-01")), 0.0);
        checkDouble("frac: no habits at all", StreakLogic.dayFraction(new ArrayList<>(), today), 0.0);

        check("tracked: before any creation", StreakLogic.trackedCount(dos, d("2026-10-31")), 0);
        check("tracked: two on Nov 16", StreakLogic.trackedCount(dos, d("2026-11-16")), 2);
        check("tracked: all three today", StreakLogic.trackedCount(dos, d("2026-11-17")), 3);

        /* ---------- weekStates ---------- */
        StreakLogic.HabitData wk = habit("do", "2026-11-01",
            set("2026-11-17", "2026-11-15", "2026-11-12"), null);
        checkWeek("week do: done pattern", StreakLogic.weekStates(wk, today), 0, 1, 0, 0, 1, 0, 1);
        StreakLogic.HabitData wa = habit("avoid", "2026-11-13", null, list("2026-11-16"));
        checkWeek("week avoid: before creation, clean, broken, today pending",
            StreakLogic.weekStates(wa, today), 0, 0, 1, 1, 1, 2, 0);
        StreakLogic.HabitData wb = habit("avoid", "2026-11-01", null, list("2026-11-17"));
        checkWeek("week avoid: broken today", StreakLogic.weekStates(wb, today), 1, 1, 1, 1, 1, 1, 2);

        /* ---------- sanitize ---------- */
        StreakLogic.HabitData ok = StreakLogic.sanitize("id1", "  Read  ", "do", "mind",
            "2026-11-01", set("2026-11-16", "bogus", "2026-11-18"), list("2026-11-10", "2026-11-10", "bad"), today);
        check("san: valid entry survives", ok != null, true);
        check("san: name trimmed", ok.name, "Read");
        check("san: category kept", ok.category, "mind");
        check("san: createdAt kept", ok.createdAt, "2026-11-01");
        check("san: bogus + future completions dropped", ok.completions, set("2026-11-16"));
        check("san: breaks deduped and bad dropped", ok.breaks, list("2026-11-10"));
        check("san: null name dropped", StreakLogic.sanitize("x", null, "do", "mind", "2026-11-01", null, null, today), null);
        check("san: blank name dropped", StreakLogic.sanitize("x", "   ", "do", "mind", "2026-11-01", null, null, today), null);
        check("san: bad type dropped", StreakLogic.sanitize("x", "Run", "maybe", "mind", "2026-11-01", null, null, today), null);
        check("san: null type dropped", StreakLogic.sanitize("x", "Run", null, "mind", "2026-11-01", null, null, today), null);
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 70; i++) longName.append('a');
        StreakLogic.HabitData ln = StreakLogic.sanitize("x", longName.toString(), "do", "mind", "2026-11-01", null, null, today);
        check("san: name truncated to 60", ln.name.length(), 60);
        StreakLogic.HabitData uc = StreakLogic.sanitize("x", "Run", "do", "nope", "2026-11-01", null, null, today);
        check("san: unknown category becomes other", uc.category, "other");
        StreakLogic.HabitData bc = StreakLogic.sanitize("x", "Run", "do", "mind", "not-a-date", null, null, today);
        check("san: bad createdAt becomes today", bc.createdAt, "2026-11-17");
        StreakLogic.HabitData ni = StreakLogic.sanitize("", "Run", "avoid", "health", "2026-11-01", null, null, today);
        check("san: empty id regenerated", ni.id != null && !ni.id.isEmpty(), true);
        StreakLogic.HabitData fb = StreakLogic.sanitize("x", "Run", "avoid", "health", "2026-11-01",
            null, list("2026-11-20", "2026-11-05", "2026-11-12"), today);
        check("san: breaks sorted, future break dropped", fb.breaks, list("2026-11-05", "2026-11-12"));
        StreakLogic.HabitData empty = StreakLogic.sanitize("x", "Run", "do", "work", "2026-11-01", null, null, today);
        check("san: null collections become empty", empty.completions.isEmpty() && empty.breaks.isEmpty(), true);

        System.out.println();
        System.out.println("StreakLogicTest: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
