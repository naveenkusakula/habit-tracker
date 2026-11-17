package com.naveenkusakula.streaks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flow tests: complete user journeys run against the app's real storage
 * and logic classes (HabitStore + StreakLogic), performing the same
 * operations MainActivity performs for each tap:
 *
 *   add habit    -> StreakLogic.sanitize(...) + list.add
 *   check / undo -> completions add / remove for "today"
 *   Broke it     -> breaks add (sorted);  Undo -> breaks remove
 *   delete       -> list.remove
 *   app restart  -> HabitStore.toJson -> HabitStore.fromJson
 *   next day     -> recompute with a later "today"
 *
 * Needs the standalone org.json jar on the classpath (test-only; the app
 * itself uses Android's built-in org.json and ships no dependencies):
 *   javac -cp json.jar -d /tmp/flow-test \
 *     src/com/naveenkusakula/streaks/StreakLogic.java \
 *     src/com/naveenkusakula/streaks/HabitStore.java test/FlowTest.java
 *   java -cp /tmp/flow-test:json.jar com.naveenkusakula.streaks.FlowTest
 * Exits 0 only when every check passes.
 */
public class FlowTest {

    private static int passed = 0, failed = 0;

    private static void check(String name, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) { passed++; }
        else {
            failed++;
            System.out.println("FAIL: " + name + " — expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static LocalDate d(String iso) { return LocalDate.parse(iso); }
    private static String f(LocalDate d) { return StreakLogic.fmt(d); }

    /* --- the operations, mirroring MainActivity --- */
    private static List<StreakLogic.HabitData> restart(List<StreakLogic.HabitData> habits, LocalDate today) {
        return HabitStore.fromJson(HabitStore.toJson(habits), today);
    }
    private static StreakLogic.HabitData addHabit(List<StreakLogic.HabitData> habits,
                                                  String name, String type, String cat, LocalDate today) {
        StreakLogic.HabitData h = StreakLogic.sanitize("", name, type, cat, "", null, null, today);
        if (h != null) habits.add(h);
        return h;
    }
    private static void toggle(StreakLogic.HabitData h, LocalDate today) {
        String t = f(today);
        if (!h.completions.remove(t)) h.completions.add(t);
    }
    private static void brokeIt(StreakLogic.HabitData h, LocalDate today) {
        String t = f(today);
        if (!h.breaks.contains(t)) { h.breaks.add(t); Collections.sort(h.breaks); }
    }
    private static void undoBreak(StreakLogic.HabitData h, LocalDate today) {
        h.breaks.remove(f(today));
    }
    private static int doneCount(List<StreakLogic.HabitData> habits, LocalDate today) {
        int n = 0;
        for (StreakLogic.HabitData h : habits) {
            if (h.type.equals("do") && h.completions.contains(f(today))) n++;
        }
        return n;
    }
    private static int doTotal(List<StreakLogic.HabitData> habits) {
        int n = 0;
        for (StreakLogic.HabitData h : habits) if (h.type.equals("do")) n++;
        return n;
    }

    public static void main(String[] args) {
        LocalDate nov17 = d("2026-11-17");
        LocalDate nov18 = d("2026-11-18");
        LocalDate nov19 = d("2026-11-19");

        /* ===== Journey 1: first launch, nothing stored ===== */
        List<StreakLogic.HabitData> habits = HabitStore.fromJson(null, nov17);
        check("J1 null storage -> empty", habits.size(), 0);
        check("J1 empty string -> empty", HabitStore.fromJson("", nov17).size(), 0);
        check("J1 garbage -> empty", HabitStore.fromJson("not json {", nov17).size(), 0);
        check("J1 empty array -> empty", HabitStore.fromJson("[]", nov17).size(), 0);
        check("J1 hero with no habits", StreakLogic.conditionText(0, 0), "No habits yet");

        /* ===== Journey 2: add a do-habit, check it, restart ===== */
        StreakLogic.HabitData read = addHabit(habits, "Read 10 pages", "do", "mind", nov17);
        check("J2 habit added", habits.size(), 1);
        check("J2 created today", read.createdAt, "2026-11-17");
        check("J2 not done yet", StreakLogic.conditionText(doneCount(habits, nov17), doTotal(habits)), "Nothing yet");
        toggle(read, nov17);
        check("J2 done -> 100%", StreakLogic.percent(doneCount(habits, nov17), doTotal(habits)), 100);
        check("J2 done -> All clear", StreakLogic.conditionText(1, 1), "All clear");
        check("J2 streak 1", StreakLogic.doStreak(read.completions, nov17), 1);
        habits = restart(habits, nov17);
        read = habits.get(0);
        check("J2 restart keeps the habit", habits.size(), 1);
        check("J2 restart keeps name", read.name, "Read 10 pages");
        check("J2 restart keeps completion", read.completions.contains("2026-11-17"), true);
        check("J2 restart keeps streak", StreakLogic.doStreak(read.completions, nov17), 1);
        toggle(read, nov17); /* undo the check */
        check("J2 un-check -> streak 0", StreakLogic.doStreak(read.completions, nov17), 0);
        toggle(read, nov17); /* check again for the rollover journey */
        habits = restart(habits, nov17);
        read = habits.get(0);

        /* ===== Journey 3: day rollover ===== */
        check("J3 next morning: streak alive on grace", StreakLogic.doStreak(read.completions, nov18), 1);
        check("J3 next morning: not yet done", StreakLogic.conditionText(doneCount(habits, nov18), 1), "Nothing yet");
        toggle(read, nov18);
        check("J3 done on day 2 -> streak 2", StreakLogic.doStreak(read.completions, nov18), 2);
        habits = restart(habits, nov18);
        read = habits.get(0);
        check("J3 day 3, day 2 done but day 3 pending: streak 2 via grace",
            StreakLogic.doStreak(read.completions, nov19), 2);
        /* user then skips day 3 entirely; on day 4 (Nov 20) the streak must be gone */
        check("J3 after a fully missed day: streak 0",
            StreakLogic.doStreak(read.completions, d("2026-11-20")), 0);

        /* ===== Journey 4: avoid habit — clean run, break, undo ===== */
        List<StreakLogic.HabitData> h2 = new ArrayList<>();
        StreakLogic.HabitData sugar = addHabit(h2, "No sugar", "avoid", "health", d("2026-11-10"));
        check("J4 created a week ago: streak 7", StreakLogic.avoidStreak(sugar.createdAt, sugar.breaks, nov17), 7);
        brokeIt(sugar, nov17);
        check("J4 broke today -> 0", StreakLogic.avoidStreak(sugar.createdAt, sugar.breaks, nov17), 0);
        h2 = restart(h2, nov17);
        sugar = h2.get(0);
        check("J4 restart keeps the break", sugar.breaks.contains("2026-11-17"), true);
        check("J4 restart still 0", StreakLogic.avoidStreak(sugar.createdAt, sugar.breaks, nov17), 0);
        undoBreak(sugar, nov17);
        check("J4 undo restores the streak", StreakLogic.avoidStreak(sugar.createdAt, sugar.breaks, nov17), 7);
        h2 = restart(h2, nov17);
        sugar = h2.get(0);
        check("J4 next day the clean streak grows", StreakLogic.avoidStreak(sugar.createdAt, sugar.breaks, nov18), 8);
        brokeIt(sugar, nov18);
        check("J4 break on day 2 -> 0 again", StreakLogic.avoidStreak(sugar.createdAt, sugar.breaks, nov18), 0);
        check("J4 and the day after a break counts 1", StreakLogic.avoidStreak(sugar.createdAt, sugar.breaks, nov19), 1);

        /* ===== Journey 5: two habits, delete one, restart ===== */
        List<StreakLogic.HabitData> h3 = new ArrayList<>();
        StreakLogic.HabitData gym = addHabit(h3, "Gym", "do", "fitness", nov17);
        addHabit(h3, "Meditate", "do", "mind", nov17);
        toggle(gym, nov17);
        check("J5 one of two done -> 50%", StreakLogic.percent(doneCount(h3, nov17), doTotal(h3)), 50);
        check("J5 condition Partly done", StreakLogic.conditionText(1, 2), "Partly done");
        h3.remove(gym);
        h3 = restart(h3, nov17);
        check("J5 after delete + restart: one habit", h3.size(), 1);
        check("J5 survivor is Meditate", h3.get(0).name, "Meditate");
        check("J5 survivor untouched (not done)", h3.get(0).completions.isEmpty(), true);

        /* ===== Journey 6: corrupted storage mid-file ===== */
        String mixed = "[{\"id\":\"a\",\"name\":\"Good\",\"type\":\"do\",\"category\":\"work\","
            + "\"createdAt\":\"2026-11-01\",\"completions\":{\"2026-11-17\":true},\"breaks\":[]},"
            + "{\"id\":\"b\",\"name\":\"\",\"type\":\"do\"},"
            + "{\"id\":\"c\",\"name\":\"Future\",\"type\":\"do\",\"category\":\"work\","
            + "\"createdAt\":\"2026-11-01\",\"completions\":{\"2999-01-01\":true},\"breaks\":[]},42]";
        List<StreakLogic.HabitData> rec = HabitStore.fromJson(mixed, nov17);
        check("J6 valid entries recovered, junk dropped", rec.size(), 2);
        check("J6 good habit intact", rec.get(0).name, "Good");
        check("J6 good completion intact", rec.get(0).completions.contains("2026-11-17"), true);
        check("J6 future completion stripped", rec.get(1).completions.isEmpty(), true);

        /* ===== Journey 7: week strip numbers after a mixed week ===== */
        List<StreakLogic.HabitData> h4 = new ArrayList<>();
        StreakLogic.HabitData run = addHabit(h4, "Run", "do", "fitness", d("2026-11-11"));
        run.completions.add("2026-11-16");
        run.completions.add("2026-11-17");
        check("J7 today 100%", (int) Math.round(StreakLogic.dayFraction(h4, nov17) * 100), 100);
        check("J7 yesterday 100%", (int) Math.round(StreakLogic.dayFraction(h4, d("2026-11-16")) * 100), 100);
        check("J7 two days ago 0%", (int) Math.round(StreakLogic.dayFraction(h4, d("2026-11-15")) * 100), 0);
        check("J7 before creation untracked", StreakLogic.trackedCount(h4, d("2026-11-10")), 0);

        System.out.println();
        System.out.println("FlowTest: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
