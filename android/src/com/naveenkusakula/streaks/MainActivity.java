package com.naveenkusakula.streaks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Streaks — a daily habit tracker that lives entirely on this device.
 *
 * Security design:
 *  - No network code exists in this app, and the manifest declares no
 *    INTERNET permission, so the OS itself blocks any connection.
 *  - No third-party libraries: only the Android framework and org.json,
 *    which ships with Android.
 *  - Data is stored in app-private SharedPreferences (MODE_PRIVATE), which
 *    Android sandboxes away from every other app.
 *  - Stored data is never trusted: every field is validated on load, the
 *    same hardening applied to the web version.
 */
public class MainActivity extends Activity {

    /* ---------------- model ---------------- */

    private static final String PREFS = "streaks_data";
    private static final String KEY = "habits_json";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String[][] CATEGORIES = {
        {"health",  "Health",  "#34d399"},
        {"fitness", "Fitness", "#fb923c"},
        {"mind",    "Mind",    "#a78bfa"},
        {"work",    "Work",    "#38bdf8"},
        {"home",    "Home",    "#facc15"},
        {"money",   "Money",   "#4ade80"},
        {"other",   "Other",   "#cbd5e1"},
    };

    private static class Habit {
        String id, name, type, category, createdAt;
        Set<String> completions = new HashSet<>();
        List<String> breaks = new ArrayList<>();
    }

    private List<Habit> habits = new ArrayList<>();
    private LinearLayout content;
    private final Handler handler = new Handler();
    private String lastRenderedDay = "";
    private String armedBreakId = null;
    private String armedDeleteId = null;

    /* ---------------- dates ---------------- */

    private static String fmt(LocalDate d) { return d.format(ISO); }
    private static String todayStr() { return fmt(LocalDate.now()); }

    private static boolean isDateStr(String s) {
        if (s == null || s.length() != 10) return false;
        try {
            LocalDate d = LocalDate.parse(s, ISO);
            return fmt(d).equals(s);
        } catch (RuntimeException e) { return false; }
    }

    /* ---------------- storage ---------------- */

    private void load() {
        habits = new ArrayList<>();
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null);
            if (raw == null) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Habit h = sanitize(arr.optJSONObject(i));
                if (h != null) habits.add(h);
            }
        } catch (Exception e) {
            habits = new ArrayList<>(); /* corrupted storage -> fresh start */
        }
    }

    /* Stored data is never trusted: validate and rebuild every field. */
    private Habit sanitize(JSONObject o) {
        if (o == null) return null;
        String type = o.optString("type", "");
        if (!type.equals("do") && !type.equals("avoid")) return null;
        String name = o.optString("name", "").trim();
        if (name.isEmpty()) return null;
        if (name.length() > 60) name = name.substring(0, 60);
        Habit h = new Habit();
        h.id = o.optString("id", "");
        if (h.id.isEmpty()) h.id = UUID.randomUUID().toString();
        h.name = name;
        h.type = type;
        h.category = "other";
        for (String[] c : CATEGORIES) if (c[0].equals(o.optString("category", ""))) h.category = c[0];
        String created = o.optString("createdAt", "");
        h.createdAt = isDateStr(created) ? created : todayStr();
        JSONObject comp = o.optJSONObject("completions");
        if (comp != null) {
            Iterator<String> keys = comp.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (isDateStr(k) && comp.optBoolean(k, false)) h.completions.add(k);
            }
        }
        JSONArray br = o.optJSONArray("breaks");
        if (br != null) {
            for (int i = 0; i < br.length(); i++) {
                String d = br.optString(i, "");
                if (isDateStr(d) && !h.breaks.contains(d)) h.breaks.add(d);
            }
            Collections.sort(h.breaks);
        }
        return h;
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Habit h : habits) {
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
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putString(KEY, arr.toString());
            e.apply();
        } catch (Exception ignored) { /* storage failure leaves in-memory state intact */ }
    }

    /* ---------------- streak math (same rules as the web app) ---------------- */

    private int doStreak(Habit h) {
        LocalDate cursor = LocalDate.now();
        if (!h.completions.contains(fmt(cursor))) cursor = cursor.minusDays(1);
        int streak = 0;
        while (h.completions.contains(fmt(cursor))) { streak++; cursor = cursor.minusDays(1); }
        return streak;
    }

    private int avoidStreak(Habit h) {
        /* Days clean = whole days since the later of createdAt and the last
           break; a break today means 0. Same rule as the web app. */
        String today = todayStr();
        if (h.breaks.contains(today)) return 0;
        String anchor = h.createdAt;
        for (String b : h.breaks) if (b.compareTo(anchor) > 0) anchor = b;
        long days = LocalDate.now().toEpochDay() - LocalDate.parse(anchor, ISO).toEpochDay();
        return (int) Math.max(0, days);
    }

    private boolean brokenToday(Habit h) { return h.breaks.contains(todayStr()); }

    private String catLabel(String id) {
        for (String[] c : CATEGORIES) if (c[0].equals(id)) return c[1];
        return "Other";
    }

    private int catColor(String id) {
        for (String[] c : CATEGORIES) if (c[0].equals(id)) return Color.parseColor(c[2]);
        return Color.parseColor("#cbd5e1");
    }

    /* ---------------- small UI helpers ---------------- */

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(radiusDp));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable circle(int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private TextView tv(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView label(String text) {
        TextView t = tv(text, 12, 0x99FFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.18f);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lpW(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.weight = weight;
        return p;
    }

    /* ---------------- lifecycle ---------------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        load();
        buildUi();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); /* re-check the day whenever the app comes back */
        handler.post(dayWatcher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(dayWatcher);
    }

    private final Runnable dayWatcher = new Runnable() {
        @Override public void run() {
            if (!todayStr().equals(lastRenderedDay)) render();
            handler.postDelayed(this, 30000);
        }
    };

    /* ---------------- layout ---------------- */

    private void buildUi() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(0xFF141a3d);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(makeBackground());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(56), dp(20), dp(120));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button fab = new Button(this);
        fab.setText("+");
        fab.setTextSize(30);
        fab.setTextColor(Color.WHITE);
        fab.setAllCaps(false);
        fab.setMinHeight(0); fab.setMinWidth(0);
        fab.setPadding(0, 0, 0, dp(4));
        GradientDrawable fabBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF8b5cf6, 0xFF2dd4bf});
        fabBg.setShape(GradientDrawable.OVAL);
        fab.setBackground(fabBg);
        fab.setElevation(dp(8));
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(62), dp(62));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.setMargins(0, 0, dp(22), dp(26));
        fab.setLayoutParams(fabLp);
        fab.setOnClickListener(v -> showAddDialog());
        root.addView(fab);

        setContentView(root);
    }

    private LayerDrawable makeBackground() {
        GradientDrawable base = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF1b2a6b, 0xFF3b2f8f, 0xFF0e7c86});
        base.setGradientType(GradientDrawable.LINEAR_GRADIENT);

        GradientDrawable blobTeal = new GradientDrawable();
        blobTeal.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        blobTeal.setColors(new int[]{0x592dd4bf, 0x002dd4bf});
        blobTeal.setGradientRadius(dp(340));
        blobTeal.setGradientCenter(0.95f, 0.02f);

        GradientDrawable blobViolet = new GradientDrawable();
        blobViolet.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        blobViolet.setColors(new int[]{0x668b5cf6, 0x008b5cf6});
        blobViolet.setGradientRadius(dp(380));
        blobViolet.setGradientCenter(0.05f, 0.95f);

        return new LayerDrawable(new android.graphics.drawable.Drawable[]{base, blobTeal, blobViolet});
    }

    /* ---------------- rendering ---------------- */

    private void render() {
        lastRenderedDay = todayStr();
        armedBreakId = null;
        armedDeleteId = null;
        content.removeAllViews();

        LocalDate today = LocalDate.now();

        TextView eyebrow = label("DAILY HABITS");
        content.addView(eyebrow);

        TextView title = tv("Today", 62, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams titleLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(2);
        title.setLayoutParams(titleLp);
        content.addView(title);

        String dateLine = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault())
            + ", " + today.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
            + " " + today.getDayOfMonth() + ", " + today.getYear();
        TextView dateTv = tv(dateLine, 15, 0xB3FFFFFF);
        LinearLayout.LayoutParams dateLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp(4);
        dateTv.setLayoutParams(dateLp);
        content.addView(dateTv);

        List<Habit> dos = new ArrayList<>(), avoids = new ArrayList<>();
        for (Habit h : habits) (h.type.equals("do") ? dos : avoids).add(h);

        int doneCount = 0;
        for (Habit h : dos) if (h.completions.contains(todayStr())) doneCount++;
        int best = 0;
        for (Habit h : habits) best = Math.max(best, h.type.equals("do") ? doStreak(h) : avoidStreak(h));

        content.addView(summaryCard(doneCount, dos.size(), best));

        /* --- Do today --- */
        content.addView(sectionHeader("DO TODAY", dos.size()));
        if (dos.isEmpty()) {
            content.addView(emptyCard("Nothing here yet. Tap + and add your first habit."));
        } else {
            for (Habit h : dos) content.addView(doCard(h));
        }

        /* --- Don't do --- */
        content.addView(sectionHeader("DON'T DO", avoids.size()));
        if (avoids.isEmpty()) {
            content.addView(emptyCard("Nothing here yet. Add something you're quitting with +."));
        } else {
            for (Habit h : avoids) content.addView(avoidCard(h));
        }

        TextView footer = tv("Your data never leaves this phone.", 12.5f, 0x73FFFFFF);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(26);
        footer.setLayoutParams(fLp);
        content.addView(footer);
    }

    private LinearLayout summaryCard(int done, int total, int best) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(0x1AFFFFFF, 0x33FFFFFF, 26));
        card.setPadding(dp(22), dp(20), dp(22), dp(20));
        LinearLayout.LayoutParams cardLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(20);
        card.setLayoutParams(cardLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);

        TextView big = tv(done + "/" + total, 42, Color.WHITE);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        row.addView(big);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = lpW(1);
        tLp.leftMargin = dp(14);
        texts.setLayoutParams(tLp);
        texts.addView(label("DONE TODAY"));
        String sub;
        if (total == 0) sub = "Add a habit to get started";
        else if (done == total) sub = "All done — nice work";
        else if (done == 0) sub = "Nothing checked off yet";
        else sub = (total - done) + " to go";
        TextView subTv = tv(sub, 14, 0xB3FFFFFF);
        LinearLayout.LayoutParams sLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(3);
        subTv.setLayoutParams(sLp);
        texts.addView(subTv);
        row.addView(texts);
        card.addView(row);

        /* progress bar: fill/spacer by weight */
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(rounded(0x2EFFFFFF, 0, 4));
        LinearLayout.LayoutParams trackLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
        trackLp.topMargin = dp(16);
        track.setLayoutParams(trackLp);
        if (total > 0 && done > 0) {
            View fill = new View(this);
            GradientDrawable fg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF34d399, 0xFF2dd4bf});
            fg.setCornerRadius(dp(4));
            fill.setBackground(fg);
            LinearLayout.LayoutParams fillLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT);
            fillLp.weight = done;
            fill.setLayoutParams(fillLp);
            track.addView(fill);
        }
        if (total == 0 || done < total) {
            View spacer = new View(this);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT);
            spLp.weight = Math.max(1, total - done);
            spacer.setLayoutParams(spLp);
            track.addView(spacer);
        }
        card.addView(track);

        if (best > 0) {
            TextView streakLine = tv("🔥 " + best + "-day streak going", 13.5f, 0xCCFFFFFF);
            LinearLayout.LayoutParams stLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = dp(12);
            streakLine.setLayoutParams(stLp);
            card.addView(streakLine);
        }
        return card;
    }

    private LinearLayout sectionHeader(String text, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams rowLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(30);
        rowLp.bottomMargin = dp(12);
        row.setLayoutParams(rowLp);
        TextView l = label(text);
        l.setLayoutParams(lpW(1));
        row.addView(l);
        row.addView(tv(String.valueOf(count), 12.5f, 0x80FFFFFF));
        return row;
    }

    private TextView emptyCard(String text) {
        TextView t = tv(text, 15, 0x99FFFFFF);
        t.setBackground(rounded(0x14FFFFFF, 0x26FFFFFF, 26));
        t.setPadding(dp(20), dp(20), dp(20), dp(20));
        t.setLineSpacing(0, 1.45f);
        return t;
    }

    /* card skeleton shared by both types */
    private LinearLayout cardShell(Habit h, boolean broken) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(broken ? 0x2EE11D48 : 0x1AFFFFFF, broken ? 0x66FB7185 : 0x33FFFFFF, 26));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams cardLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        card.setLayoutParams(cardLp);
        return card;
    }

    private LinearLayout cardBody(Habit h, String streakText, String noteText) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(lpW(1));

        /* top row: dot + category + delete */
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(circle(catColor(h.category), 0, 0));
        LinearLayout.LayoutParams dotLp = lp(dp(8), dp(8));
        dotLp.rightMargin = dp(7);
        dot.setLayoutParams(dotLp);
        top.addView(dot);
        TextView cat = tv(catLabel(h.category).toUpperCase(Locale.US), 11, 0xB3FFFFFF);
        cat.setTypeface(Typeface.DEFAULT_BOLD);
        cat.setLetterSpacing(0.12f);
        cat.setLayoutParams(lpW(1));
        top.addView(cat);

        final Habit habit = h;
        TextView del = tv(armedDeleteId != null && armedDeleteId.equals(h.id) ? "Delete?" : "×",
            armedDeleteId != null && armedDeleteId.equals(h.id) ? 13 : 19,
            armedDeleteId != null && armedDeleteId.equals(h.id) ? 0xFFFB7185 : 0x80FFFFFF);
        if (armedDeleteId != null && armedDeleteId.equals(h.id)) del.setTypeface(Typeface.DEFAULT_BOLD);
        del.setPadding(dp(8), dp(2), dp(2), dp(2));
        del.setOnClickListener(v -> {
            if (h.id.equals(armedDeleteId)) {
                habits.remove(habit);
                save();
                render();
            } else {
                armedDeleteId = h.id;
                render();
                handler.postDelayed(this::render, 2600);
            }
        });
        top.addView(del);
        body.addView(top);

        TextView name = tv(h.name, 20, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(5);
        name.setLayoutParams(nLp);
        body.addView(name);

        TextView streak = tv(streakText, 13.5f, 0xCCFFFFFF);
        LinearLayout.LayoutParams sLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(3);
        streak.setLayoutParams(sLp);
        body.addView(streak);

        if (noteText != null) {
            TextView note = tv(noteText, 13, 0xFFFDA4AF);
            LinearLayout.LayoutParams noLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noLp.topMargin = dp(3);
            note.setLayoutParams(noLp);
            body.addView(note);
        }

        body.addView(weekRow(h));
        return body;
    }

    private LinearLayout weekRow(Habit h) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        row.setLayoutParams(rowLp);
        LocalDate today = LocalDate.now();
        String letters = "MTWTFSS";
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            String ds = fmt(d);
            boolean isToday = i == 0;
            boolean filled;
            boolean bad = false;
            if (h.type.equals("do")) {
                filled = h.completions.contains(ds);
            } else {
                bad = h.breaks.contains(ds);
                filled = !bad && !isToday && ds.compareTo(h.createdAt) >= 0;
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cellLp = lp(dp(23), ViewGroup.LayoutParams.WRAP_CONTENT);
            cell.setLayoutParams(cellLp);

            View dotView = new View(this);
            int fill, stroke = 0;
            if (h.type.equals("avoid") && bad) { fill = 0xFFE11D48; }
            else if (filled) { fill = h.type.equals("do") ? 0xFF34D399 : 0x40FFFFFF; }
            else { fill = 0x1AFFFFFF; }
            if (isToday) stroke = 0xFFFFFFFF;
            dotView.setBackground(circle(fill, stroke, 1.5f));
            dotView.setLayoutParams(lp(dp(14), dp(14)));
            cell.addView(dotView);

            TextView letter = tv(String.valueOf(letters.charAt(d.getDayOfWeek().getValue() - 1)), 9,
                isToday ? 0xE6FFFFFF : 0x66FFFFFF);
            LinearLayout.LayoutParams lLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lLp.topMargin = dp(3);
            letter.setLayoutParams(lLp);
            cell.addView(letter);
            row.addView(cell);
        }
        return row;
    }

    private LinearLayout doCard(final Habit h) {
        boolean done = h.completions.contains(todayStr());
        int streak = doStreak(h);
        String streakText = streak > 0
            ? "🔥 " + streak + (streak == 1 ? " day" : " days") + " streak"
            : (done ? "Done — streak starts today" : "Not done yet today");
        LinearLayout card = cardShell(h, false);
        card.addView(cardBody(h, streakText, null));

        Button check = new Button(this);
        check.setText("✓");
        check.setTextSize(25);
        check.setTextColor(Color.WHITE);
        check.setAllCaps(false);
        check.setMinHeight(0); check.setMinWidth(0);
        check.setPadding(0, 0, 0, dp(3));
        if (done) {
            GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF34d399, 0xFF059669});
            g.setShape(GradientDrawable.OVAL);
            check.setBackground(g);
        } else {
            check.setBackground(circle(0x14FFFFFF, 0x80FFFFFF, 2));
        }
        LinearLayout.LayoutParams cLp = lp(dp(58), dp(58));
        cLp.leftMargin = dp(12);
        check.setLayoutParams(cLp);
        check.setOnClickListener(v -> {
            if (done) h.completions.remove(todayStr());
            else h.completions.add(todayStr());
            save();
            render();
        });
        card.addView(check);
        return card;
    }

    private LinearLayout avoidCard(final Habit h) {
        boolean broke = brokenToday(h);
        int streak = avoidStreak(h);
        String streakText = broke
            ? "Streak reset today"
            : "🛡 " + streak + (streak == 1 ? " day" : " days") + " clean";
        String note = broke ? "Streak broken today — back to 0. Tomorrow it starts again." : null;
        LinearLayout card = cardShell(h, broke);
        card.addView(cardBody(h, streakText, note));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams sideLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sideLp.leftMargin = dp(12);
        side.setLayoutParams(sideLp);

        if (broke) {
            TextView big = tv("0", 38, 0xFFFDA4AF);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            big.setGravity(Gravity.CENTER);
            side.addView(big);
            side.addView(tv("DAY STREAK", 10, 0x99FFFFFF));
            Button undo = pillButton("Undo", false);
            LinearLayout.LayoutParams uLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            uLp.topMargin = dp(10);
            undo.setLayoutParams(uLp);
            undo.setOnClickListener(v -> {
                h.breaks.remove(todayStr());
                save();
                render();
            });
            side.addView(undo);
        } else {
            TextView big = tv(String.valueOf(streak), 38, Color.WHITE);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            big.setGravity(Gravity.CENTER);
            side.addView(big);
            side.addView(tv("DAY STREAK", 10, 0x99FFFFFF));
            boolean armed = h.id.equals(armedBreakId);
            Button brokeBtn = pillButton(armed ? "Tap again — reset to 0" : "Broke it", armed);
            LinearLayout.LayoutParams bLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            bLp.topMargin = dp(10);
            brokeBtn.setLayoutParams(bLp);
            brokeBtn.setOnClickListener(v -> {
                if (h.id.equals(armedBreakId)) {
                    if (!h.breaks.contains(todayStr())) h.breaks.add(todayStr());
                    Collections.sort(h.breaks);
                    save();
                    render();
                } else {
                    armedBreakId = h.id;
                    render();
                    handler.postDelayed(this::render, 2600);
                }
            });
            side.addView(brokeBtn);
        }
        card.addView(side);
        return card;
    }

    private Button pillButton(String text, boolean dangerSolid) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(0); b.setMinWidth(0);
        b.setPadding(dp(16), 0, dp(16), 0);
        if (dangerSolid) {
            b.setTextColor(Color.WHITE);
            b.setBackground(rounded(0xFFE11D48, 0, 20));
        } else if (text.equals("Broke it")) {
            b.setTextColor(0xFFFDA4AF);
            b.setBackground(rounded(0x26E11D48, 0x59E11D48, 20));
        } else {
            b.setTextColor(Color.WHITE);
            b.setBackground(rounded(0x1AFFFFFF, 0x33FFFFFF, 20));
        }
        return b;
    }

    /* ---------------- add-habit dialog ---------------- */

    private void showAddDialog() {
        final String[] selType = {"do"};
        final String[] selCat = {"health"};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(22), dp(22), dp(6));

        TextView heading = tv("New habit", 22, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);

        final EditText name = new EditText(this);
        name.setHint("e.g. Read 10 pages");
        name.setHintTextColor(0x80FFFFFF);
        name.setTextColor(Color.WHITE);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setBackground(rounded(0x1AFFFFFF, 0x33FFFFFF, 14));
        name.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams nameLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(16);
        name.setLayoutParams(nameLp);
        box.addView(name);

        TextView typeLabel = label("TYPE");
        LinearLayout.LayoutParams tlLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.topMargin = dp(20); tlLp.bottomMargin = dp(8);
        typeLabel.setLayoutParams(tlLp);
        box.addView(typeLabel);

        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button doBtn = new Button(this);
        final Button avoidBtn = new Button(this);
        for (Button b : new Button[]{doBtn, avoidBtn}) {
            b.setTextSize(14.5f);
            b.setAllCaps(false);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setMinHeight(0); b.setMinWidth(0);
            LinearLayout.LayoutParams blp = lpW(1);
            blp.height = dp(46);
            b.setLayoutParams(blp);
        }
        LinearLayout.LayoutParams avoidLp = (LinearLayout.LayoutParams) avoidBtn.getLayoutParams();
        avoidLp.leftMargin = dp(10);
        doBtn.setText("✓  Do it");
        avoidBtn.setText("✕  Don't do it");
        segRow.addView(doBtn);
        segRow.addView(avoidBtn);
        box.addView(segRow);

        TextView catLabel = label("CATEGORY");
        LinearLayout.LayoutParams clLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clLp.topMargin = dp(20); clLp.bottomMargin = dp(8);
        catLabel.setLayoutParams(clLp);
        box.addView(catLabel);

        final List<Button> chipButtons = new ArrayList<>();
        LinearLayout chipRow = null;
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (i % 3 == 0) {
                chipRow = new LinearLayout(this);
                chipRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rLp.topMargin = dp(8);
                chipRow.setLayoutParams(rLp);
                box.addView(chipRow);
            }
            final String catId = CATEGORIES[i][0];
            Button chip = new Button(this);
            SpannableString ss = new SpannableString("● " + CATEGORIES[i][1]);
            ss.setSpan(new ForegroundColorSpan(Color.parseColor(CATEGORIES[i][2])), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            chip.setText(ss);
            chip.setTextSize(13.5f);
            chip.setAllCaps(false);
            chip.setMinHeight(0); chip.setMinWidth(0);
            chip.setPadding(dp(6), 0, dp(6), 0);
            LinearLayout.LayoutParams chLp = lpW(1);
            chLp.height = dp(42);
            if (i % 3 != 0) chLp.leftMargin = dp(8);
            chip.setLayoutParams(chLp);
            chip.setTag(catId);
            chip.setOnClickListener(v -> { selCat[0] = catId; restyleChips(chipButtons, selCat[0]); });
            chipButtons.add(chip);
            chipRow.addView(chip);
        }

        final Runnable restyleSeg = () -> {
            boolean isDo = selType[0].equals("do");
            doBtn.setTextColor(Color.WHITE);
            avoidBtn.setTextColor(Color.WHITE);
            GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                isDo ? new int[]{0xFF34d399, 0xFF059669} : new int[]{0xFF8b5cf6, 0xFF6d28d9});
            on.setCornerRadius(dp(14));
            GradientDrawable on2 = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                !isDo ? new int[]{0xFFf43f5e, 0xFFbe123c} : new int[]{0xFF8b5cf6, 0xFF6d28d9});
            on2.setCornerRadius(dp(14));
            doBtn.setBackground(isDo ? on : rounded(0x1AFFFFFF, 0x33FFFFFF, 14));
            avoidBtn.setBackground(!isDo ? on2 : rounded(0x1AFFFFFF, 0x33FFFFFF, 14));
        };
        doBtn.setOnClickListener(v -> { selType[0] = "do"; restyleSeg.run(); });
        avoidBtn.setOnClickListener(v -> { selType[0] = "avoid"; restyleSeg.run(); });
        restyleSeg.run();
        restyleChips(chipButtons, selCat[0]);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add habit", null)
            .create();
        dialog.setOnShowListener(d -> {
            Window dw = dialog.getWindow();
            if (dw != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF21C2242);
                bg.setCornerRadius(dp(24));
                bg.setStroke(dp(1), 0x33FFFFFF);
                dw.setBackgroundDrawable(bg);
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) {
                    Toast.makeText(this, "Give your habit a name first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (n.length() > 60) n = n.substring(0, 60);
                Habit h = new Habit();
                h.id = UUID.randomUUID().toString();
                h.name = n;
                h.type = selType[0];
                h.category = selCat[0];
                h.createdAt = todayStr();
                habits.add(h);
                save();
                render();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void restyleChips(List<Button> chips, String selected) {
        for (Button chip : chips) {
            boolean on = selected.equals(chip.getTag());
            chip.setTextColor(on ? Color.WHITE : 0xB3FFFFFF);
            chip.setBackground(rounded(on ? 0x38FFFFFF : 0x14FFFFFF, on ? 0x80FFFFFF : 0x26FFFFFF, 14));
        }
    }
}
