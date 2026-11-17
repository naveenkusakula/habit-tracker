package com.naveenkusakula.streaks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Serialization for the habit list — the exact code the app uses to save
 * and reload its SharedPreferences payload. Kept separate from the
 * Activity so the flow tests (test/FlowTest.java) can run full user
 * journeys — add, toggle, break, restart, next day — against the real
 * storage code on a plain JVM.
 *
 * On Android, org.json is part of the framework (no dependency). The flow
 * tests use the standalone org.json jar, which is the same library.
 *
 * Format (unchanged since v1.0, so existing installs keep their data):
 * [ { id, name, type, category, createdAt,
 *     completions: { "YYYY-MM-DD": true, ... }, breaks: [ "YYYY-MM-DD", ... ] } ]
 */
public final class HabitStore {

    private HabitStore() {}

    public static String toJson(List<StreakLogic.HabitData> habits) {
        try {
            JSONArray arr = new JSONArray();
            for (StreakLogic.HabitData h : habits) {
                JSONObject o = new JSONObject();
                o.put("id", h.id);
                o.put("name", h.name);
                o.put("type", h.type);
                o.put("category", h.category);
                o.put("createdAt", h.createdAt);
                JSONObject comp = new JSONObject();
                for (String d : h.completions) comp.put(d, true);
                o.put("completions", comp);
                JSONArray br = new JSONArray();
                for (String d : h.breaks) br.put(d);
                o.put("breaks", br);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return null; /* caller keeps its in-memory state */
        }
    }

    /** Never throws: corrupt or tampered payloads degrade to whatever
        valid habits can be recovered, or an empty list. */
    public static List<StreakLogic.HabitData> fromJson(String raw, LocalDate today) {
        List<StreakLogic.HabitData> out = new ArrayList<>();
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Set<String> comp = new HashSet<>();
                JSONObject co = o.optJSONObject("completions");
                if (co != null) {
                    Iterator<String> keys = co.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        if (co.optBoolean(k, false)) comp.add(k);
                    }
                }
                List<String> br = new ArrayList<>();
                JSONArray ba = o.optJSONArray("breaks");
                if (ba != null) for (int j = 0; j < ba.length(); j++) br.add(ba.optString(j, ""));
                StreakLogic.HabitData h = StreakLogic.sanitize(
                    o.optString("id", ""), o.optString("name", ""), o.optString("type", ""),
                    o.optString("category", ""), o.optString("createdAt", ""), comp, br, today);
                if (h != null) out.add(h);
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }
}
