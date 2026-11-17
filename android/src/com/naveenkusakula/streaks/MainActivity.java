package com.naveenkusakula.streaks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.TypedValue;
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

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Streaks — a daily habit tracker that lives entirely on this device.
 *
 * Design: native Material Design 3, the way Google's own apps look —
 * light surface, white cards with large rounded corners, Google-blue
 * primary, tonal containers, an extended FAB, segmented buttons and
 * filter chips in the add dialog.
 *
 * Security design:
 *  - No network code exists in this app, and the manifest declares no
 *    INTERNET permission, so the OS itself blocks any connection.
 *  - No third-party libraries: only the Android framework and org.json,
 *    which ships with Android.
 *  - Data is stored in app-private SharedPreferences (MODE_PRIVATE), which
 *    Android sandboxes away from every other app.
 *  - All habit rules live in StreakLogic (pure Java, unit-tested); stored
 *    data passes through StreakLogic.sanitize() before it is trusted.
 *  - The UI itself is exercised by Robolectric tests (android/robolectric)
 *    that tap through the real flows: add, check, break, undo, delete.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "streaks_data";
    private static final String KEY = "habits_json";

    private static final String[][] CATEGORIES = {
        {"health",  "Health"},
        {"fitness", "Fitness"},
        {"mind",    "Mind"},
        {"work",    "Work"},
        {"home",    "Home"},
        {"money",   "Money"},
        {"other",   "Other"},
    };

    /* Material 3 light scheme (Google apps palette) */
    private static final int SURFACE = 0xFFF8FAFD;
    private static final int CARD = Color.WHITE;
    private static final int OUTLINE = 0xFFDDE3EA;
    private static final int HAIR = 0xFFE7EAEE;
    private static final int PRIMARY = 0xFF0B57D0;
    private static final int ON_PRIMARY = Color.WHITE;
    private static final int PRIMARY_CONTAINER = 0xFFD3E3FD;
    private static final int ON_PRIMARY_CONTAINER = 0xFF041E49;
    private static final int SECONDARY_CONTAINER = 0xFFC2E7FF;
    private static final int ON_SECONDARY_CONTAINER = 0xFF001D35;
    private static final int ERROR = 0xFFB3261E;
    private static final int ERROR_CONTAINER = 0xFFF9DEDC;
    private static final int ON_ERROR_CONTAINER = 0xFF410E0B;
    private static final int ON_SURFACE = 0xFF1F1F1F;
    private static final int VARIANT = 0xFF444746;
    private static final int OUTLINE_VAR = 0xFF747775;
    private static final int FIELD_BG = 0xFFE9EEF6;
    private static final int RING_TRACK = 0xFFE7EAEE;

    private List<StreakLogic.HabitData> habits = new ArrayList<>();
    private FrameLayout root;
    private LinearLayout content;
    private final Handler handler = new Handler();
    private String lastRenderedDay = "";
    private String armedBreakId = null;
    private long armSeq = 0; /* token so a stale disarm timer can't clear a newer arm */

    /* ---------------- storage ---------------- */

    private void load() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null);
        habits = HabitStore.fromJson(raw, LocalDate.now());
    }

    private void save() {
        String json = HabitStore.toJson(habits);
        if (json == null) return; /* keep in-memory state; try again next change */
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString(KEY, json);
        e.apply();
    }

    /* ---------------- helpers ---------------- */

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView medium(String text, float sp, int color) {
        TextView t = tv(text, sp, color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapW(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.weight = weight;
        return p;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(radiusDp));
        g.setColor(color);
        return g;
    }

    private LinearLayout card(float radiusDp) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(CARD, radiusDp);
        bg.setStroke(dp(1), OUTLINE);
        c.setBackground(bg);
        c.setElevation(dp(1));
        return c;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(HAIR);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private void ripple(View v) {
        TypedValue out = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            v.setBackgroundResource(out.resourceId);
        }
    }

    private String catLabel(String id) {
        for (String[] c : CATEGORIES) if (c[0].equals(id)) return c[1];
        return "Other";
    }

    private String prettyDate(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso, StreakLogic.ISO);
            return d.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.getDayOfMonth();
        } catch (RuntimeException e) { return iso; }
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
        render();
        handler.post(dayWatcher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(dayWatcher);
    }

    private final Runnable dayWatcher = new Runnable() {
        @Override public void run() {
            if (!StreakLogic.fmt(LocalDate.now()).equals(lastRenderedDay)) render();
            handler.postDelayed(this, 30000);
        }
    };

    /* ---------------- layout ---------------- */

    private void buildUi() {
        Window w = getWindow();
        w.setStatusBarColor(SURFACE);
        w.setNavigationBarColor(SURFACE);
        w.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        root = new FrameLayout(this);
        root.setBackgroundColor(SURFACE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(120));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* Extended FAB, bottom-right — the Material way to add. */
        Button fab = new Button(this);
        fab.setText("+  New habit");
        fab.setTextSize(15);
        fab.setTextColor(ON_PRIMARY_CONTAINER);
        fab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fab.setAllCaps(false);
        fab.setMinHeight(0); fab.setMinWidth(0);
        fab.setPadding(dp(20), dp(14), dp(20), dp(14));
        fab.setBackground(rounded(PRIMARY_CONTAINER, 16));
        fab.setElevation(dp(6));
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = dp(24);
        fabLp.bottomMargin = dp(24);
        fab.setLayoutParams(fabLp);
        fab.setOnClickListener(v -> showAddDialog());
        root.addView(fab);

        setContentView(root);
    }

    /* ---------------- rendering ---------------- */

    private void render() {
        LocalDate today = LocalDate.now();
        String todayS = StreakLogic.fmt(today);
        lastRenderedDay = todayS;
        /* NOTE: armedBreakId must NOT be cleared here. It used to be, which
           silently broke the Don't-do flow: the first tap on "Broke it"
           armed the button and immediately called render(), which wiped
           the armed state before the row was rebuilt — so the confirming
           second tap could never happen. It is cleared explicitly where a
           break is recorded, undone, or timed out (see avoidRow). */
        content.removeAllViews();

        List<StreakLogic.HabitData> dos = new ArrayList<>(), avoids = new ArrayList<>();
        for (StreakLogic.HabitData h : habits) (h.type.equals("do") ? dos : avoids).add(h);
        int done = 0;
        for (StreakLogic.HabitData h : dos) if (h.completions.contains(todayS)) done++;
        int total = dos.size();
        int best = 0;
        for (StreakLogic.HabitData h : habits) {
            best = Math.max(best, h.type.equals("do")
                ? StreakLogic.doStreak(h.completions, today)
                : StreakLogic.avoidStreak(h.createdAt, h.breaks, today));
        }

        /* app bar: title + date, left-aligned like Google apps */
        content.addView(tv("Streaks", 22, ON_SURFACE));
        String dateLine = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault())
            + ", " + today.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
            + " " + today.getDayOfMonth();
        TextView dateTv = tv(dateLine, 14, VARIANT);
        LinearLayout.LayoutParams dLp = wrap();
        dLp.topMargin = dp(4);
        dateTv.setLayoutParams(dLp);
        content.addView(dateTv);

        /* hero card: progress ring + today's condition */
        LinearLayout hero = card(28);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = dp(20);
        hero.setLayoutParams(heroLp);
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);

        int pct = total == 0 ? 0 : StreakLogic.percent(done, total);
        FrameLayout ringBox = new FrameLayout(this);
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(dp(104), dp(104));
        ringBox.setLayoutParams(rbLp);
        ringBox.addView(new RingView(pct), new FrameLayout.LayoutParams(dp(104), dp(104)));
        TextView pctTv = tv(total == 0 ? "–" : pct + "%", 26, ON_SURFACE);
        pctTv.setGravity(Gravity.CENTER);
        ringBox.addView(pctTv, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        heroTop.addView(ringBox);

        LinearLayout heroTexts = new LinearLayout(this);
        heroTexts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htLp = wrapW(1);
        htLp.leftMargin = dp(20);
        heroTexts.setLayoutParams(htLp);
        heroTexts.addView(medium(StreakLogic.conditionText(done, total), 20, ON_SURFACE));
        TextView heroSub = tv(total == 0
            ? "Add a habit to start your day"
            : done + " of " + total + " done today", 14, VARIANT);
        LinearLayout.LayoutParams hsLp = wrap();
        hsLp.topMargin = dp(6);
        heroSub.setLayoutParams(hsLp);
        heroTexts.addView(heroSub);
        if (best > 0) {
            TextView bestTv = tv("Best streak · " + best + (best == 1 ? " day" : " days"), 14, VARIANT);
            LinearLayout.LayoutParams bLp = wrap();
            bLp.topMargin = dp(3);
            bestTv.setLayoutParams(bLp);
            heroTexts.addView(bestTv);
        }
        heroTop.addView(heroTexts);
        hero.addView(heroTop);

        if (!dos.isEmpty()) {
            View div = hairline();
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            divLp.topMargin = dp(18);
            divLp.bottomMargin = dp(14);
            div.setLayoutParams(divLp);
            hero.addView(div);
            TextView weekLabel = medium("This week", 14, ON_SURFACE);
            hero.addView(weekLabel);
            hero.addView(weekStrip(dos, today));
        }
        content.addView(hero);

        /* do today */
        content.addView(sectionTitle("Do today"));
        if (dos.isEmpty()) {
            content.addView(emptyLine("Nothing here yet — your first habit is one tap away."));
        } else {
            LinearLayout card = card(24);
            for (int i = 0; i < dos.size(); i++) {
                if (i > 0) card.addView(hairline());
                card.addView(doRow(dos.get(i), today));
            }
            content.addView(card);
        }

        /* don't do */
        content.addView(sectionTitle("Don't do"));
        if (avoids.isEmpty()) {
            content.addView(emptyLine("Nothing here yet — add something you're quitting."));
        } else {
            LinearLayout card = card(24);
            for (int i = 0; i < avoids.size(); i++) {
                if (i > 0) card.addView(hairline());
                card.addView(avoidRow(avoids.get(i), today));
            }
            content.addView(card);
        }

        TextView footer = tv("Your data never leaves this phone.", 12, VARIANT);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = wrap();
        fLp.topMargin = dp(28);
        fLp.gravity = Gravity.CENTER_HORIZONTAL;
        footer.setLayoutParams(fLp);
        content.addView(footer);
    }

    private TextView sectionTitle(String text) {
        TextView t = medium(text, 16, ON_SURFACE);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(26);
        lp.leftMargin = dp(4);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView emptyLine(String text) {
        TextView t = tv(text, 14, VARIANT);
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** Google Fit-style progress ring. */
    private final class RingView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int pct;

        RingView(int pct) {
            super(MainActivity.this);
            this.pct = pct;
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(dp(10));
            trackPaint.setColor(RING_TRACK);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(10));
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setColor(PRIMARY);
        }

        @Override protected void onDraw(Canvas canvas) {
            float pad = dp(6);
            RectF r = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);
            canvas.drawArc(r, 0, 360, false, trackPaint);
            if (pct > 0) canvas.drawArc(r, -90, pct * 3.6f, false, ringPaint);
        }
    }

    private LinearLayout weekStrip(List<StreakLogic.HabitData> dos, LocalDate today) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(12);
        strip.setLayoutParams(sLp);
        String letters = "MTWTFSS";
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            boolean isToday = i == 0;
            int tracked = StreakLogic.trackedCount(dos, day);
            double frac = StreakLogic.dayFraction(dos, day);

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setLayoutParams(wrapW(1));

            TextView letter = tv(String.valueOf(letters.charAt(day.getDayOfWeek().getValue() - 1)),
                12, isToday ? ON_SURFACE : VARIANT);
            if (isToday) letter.setTypeface(Typeface.DEFAULT_BOLD);
            cell.addView(letter);

            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            if (tracked == 0) {
                dotBg.setColor(0x00000000);
                dotBg.setStroke(dp(1), OUTLINE_VAR);
            } else {
                int alpha = 60 + (int) Math.round(frac * 195);
                dotBg.setColor((alpha << 24) | (PRIMARY & 0x00FFFFFF));
            }
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(11), dp(11));
            dotLp.topMargin = dp(8);
            dot.setLayoutParams(dotLp);
            cell.addView(dot);

            TextView pct = tv(tracked == 0 ? "–" : Math.round(frac * 100) + "%",
                11, isToday ? ON_SURFACE : VARIANT);
            LinearLayout.LayoutParams pctLp = wrap();
            pctLp.topMargin = dp(6);
            pct.setLayoutParams(pctLp);
            cell.addView(pct);
            strip.addView(cell);
        }
        return strip;
    }

    private LinearLayout doRow(final StreakLogic.HabitData h, LocalDate today) {
        final String todayS = StreakLogic.fmt(today);
        final boolean done = h.completions.contains(todayS);
        int streak = StreakLogic.doStreak(h.completions, today);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        ripple(row);

        TextView check = tv(done ? "✓" : "", 15, ON_PRIMARY);
        check.setGravity(Gravity.CENTER);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        if (done) cg.setColor(PRIMARY);
        else { cg.setColor(0x00000000); cg.setStroke(dp(2), OUTLINE_VAR); }
        check.setBackground(cg);
        LinearLayout.LayoutParams cgLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        cgLp.rightMargin = dp(16);
        check.setLayoutParams(cgLp);
        row.addView(check);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(wrapW(1));
        texts.addView(tv(h.name, 16, ON_SURFACE));
        String sub = catLabel(h.category)
            + (streak > 0 ? " · " + streak + " day streak"
               : done ? " · Done today" : " · Not done yet");
        TextView subTv = tv(sub, 13, VARIANT);
        LinearLayout.LayoutParams subLp = wrap();
        subLp.topMargin = dp(3);
        subTv.setLayoutParams(subLp);
        texts.addView(subTv);
        row.addView(texts);

        View.OnClickListener toggle = v -> {
            if (done) h.completions.remove(todayS); else h.completions.add(todayS);
            save();
            render();
        };
        check.setOnClickListener(toggle);
        row.setOnClickListener(toggle);
        row.setOnLongClickListener(v -> { confirmDelete(h); return true; });
        return row;
    }

    private LinearLayout avoidRow(final StreakLogic.HabitData h, LocalDate today) {
        final String todayS = StreakLogic.fmt(today);
        final boolean broke = h.breaks.contains(todayS);
        final int streak = StreakLogic.avoidStreak(h.createdAt, h.breaks, today);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(wrapW(1));
        texts.addView(tv(h.name, 16, ON_SURFACE));
        TextView subTv = tv(broke
            ? catLabel(h.category) + " · Broken today — back to 0"
            : catLabel(h.category) + " · since " + prettyDate(h.createdAt), 13, broke ? ERROR : VARIANT);
        LinearLayout.LayoutParams subLp = wrap();
        subLp.topMargin = dp(3);
        subTv.setLayoutParams(subLp);
        texts.addView(subTv);

        final boolean armed = h.id.equals(armedBreakId);
        TextView action = medium(broke ? "Undo" : armed ? "Tap again — reset to 0" : "Broke it",
            14, broke ? PRIMARY : armed ? ON_ERROR_CONTAINER : ERROR);
        if (armed) {
            action.setBackground(rounded(ERROR_CONTAINER, 20));
            action.setPadding(dp(12), dp(7), dp(12), dp(7));
        } else {
            action.setPadding(0, dp(8), dp(4), dp(2));
            ripple(action);
        }
        action.setOnClickListener(v -> {
            if (broke) {
                h.breaks.remove(todayS);
                armedBreakId = null;
                save();
                render();
            } else if (armed) {
                if (!h.breaks.contains(todayS)) h.breaks.add(todayS);
                java.util.Collections.sort(h.breaks);
                armedBreakId = null;
                save();
                render();
            } else {
                armedBreakId = h.id;
                final long seq = ++armSeq;
                render();
                handler.postDelayed(() -> {
                    if (armSeq == seq) { armedBreakId = null; render(); }
                }, 2600);
            }
        });
        LinearLayout.LayoutParams actLp = wrap();
        actLp.topMargin = dp(6);
        action.setLayoutParams(actLp);
        texts.addView(action);
        row.addView(texts);

        LinearLayout numCol = new LinearLayout(this);
        numCol.setOrientation(LinearLayout.VERTICAL);
        numCol.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams ncLp = wrap();
        ncLp.leftMargin = dp(16);
        numCol.setLayoutParams(ncLp);
        numCol.addView(tv(String.valueOf(streak), 26, broke ? ERROR : ON_SURFACE));
        TextView daysLabel = tv(streak == 1 ? "day" : "days", 11, VARIANT);
        LinearLayout.LayoutParams dlLp = wrap();
        dlLp.topMargin = dp(2);
        daysLabel.setLayoutParams(dlLp);
        numCol.addView(daysLabel);
        row.addView(numCol);

        row.setOnLongClickListener(v -> { confirmDelete(h); return true; });
        return row;
    }

    /* ---------------- dialogs ---------------- */

    /** Material dialog surface: white, 28dp corners, tonal buttons. */
    private void styleDialog(AlertDialog dialog, boolean destructivePositive) {
        Window dw = dialog.getWindow();
        if (dw != null) dw.setBackgroundDrawable(rounded(CARD, 28));
        Button pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setTextColor(destructivePositive ? ERROR : PRIMARY);
            pos.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            pos.setAllCaps(false);
        }
        Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (neg != null) { neg.setTextColor(VARIANT); neg.setAllCaps(false); }
    }

    private void confirmDelete(final StreakLogic.HabitData h) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Delete habit?")
            .setMessage("“" + h.name + "” and its streak history will be gone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (d, which) -> {
                habits.remove(h);
                if (h.id.equals(armedBreakId)) armedBreakId = null;
                save();
                render();
            })
            .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, true));
        dialog.show();
    }

    private void showAddDialog() {
        final String[] selType = {"do"};
        final String[] selCat = {"health"};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(10));

        TextView heading = tv("New habit", 22, ON_SURFACE);
        box.addView(heading);

        final EditText name = new EditText(this);
        name.setHint("e.g. Read 10 pages");
        name.setHintTextColor(VARIANT);
        name.setTextColor(ON_SURFACE);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setBackground(rounded(FIELD_BG, 12));
        name.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(18);
        name.setLayoutParams(nameLp);
        box.addView(name);

        TextView typeLabel = medium("Type", 14, ON_SURFACE);
        LinearLayout.LayoutParams tlLp = wrap();
        tlLp.topMargin = dp(22); tlLp.bottomMargin = dp(8);
        typeLabel.setLayoutParams(tlLp);
        box.addView(typeLabel);

        /* Material segmented button: one outlined pill, two segments. */
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBg = rounded(0x00000000, 20);
        segBg.setStroke(dp(1), OUTLINE_VAR);
        seg.setBackground(segBg);
        final TextView doOpt = segment("Do it");
        final TextView avoidOpt = segment("Don't do it");
        seg.addView(doOpt);
        seg.addView(avoidOpt);
        box.addView(seg);

        TextView catLabelTv = medium("Category", 14, ON_SURFACE);
        LinearLayout.LayoutParams clLp = wrap();
        clLp.topMargin = dp(22); clLp.bottomMargin = dp(8);
        catLabelTv.setLayoutParams(clLp);
        box.addView(catLabelTv);

        /* Filter chips, three per row. */
        final List<TextView> chips = new ArrayList<>();
        LinearLayout chipRow = null;
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (i % 3 == 0) {
                chipRow = new LinearLayout(this);
                chipRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                crLp.bottomMargin = dp(8);
                chipRow.setLayoutParams(crLp);
                box.addView(chipRow);
            }
            final String catId = CATEGORIES[i][0];
            TextView chip = tv(CATEGORIES[i][1], 14, ON_SURFACE);
            chip.setTag(catId);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams chipLp = wrap();
            chipLp.rightMargin = dp(8);
            chip.setLayoutParams(chipLp);
            chip.setOnClickListener(v -> { selCat[0] = catId; restyleChips(chips, selCat[0]); });
            chips.add(chip);
            chipRow.addView(chip);
        }

        final Runnable restyleSeg = () -> {
            styleSegment(doOpt, selType[0].equals("do"), "Do it");
            styleSegment(avoidOpt, selType[0].equals("avoid"), "Don't do it");
        };
        doOpt.setOnClickListener(v -> { selType[0] = "do"; restyleSeg.run(); });
        avoidOpt.setOnClickListener(v -> { selType[0] = "avoid"; restyleSeg.run(); });
        restyleSeg.run();
        restyleChips(chips, selCat[0]);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add habit", null)
            .create();
        dialog.setOnShowListener(d -> {
            styleDialog(dialog, false);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) {
                    Toast.makeText(this, "Give your habit a name first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                StreakLogic.HabitData h = StreakLogic.sanitize(
                    "", n, selType[0], selCat[0], "", null, null, LocalDate.now());
                if (h != null) {
                    habits.add(h);
                    save();
                    render();
                }
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private TextView segment(String text) {
        TextView t = tv(text, 14, ON_SURFACE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(9), dp(10), dp(9));
        t.setLayoutParams(wrapW(1));
        return t;
    }

    private void styleSegment(TextView seg, boolean selected, String label) {
        seg.setText(selected ? "✓  " + label : label);
        seg.setTextColor(selected ? ON_SECONDARY_CONTAINER : ON_SURFACE);
        seg.setTypeface(selected
            ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(20));
        bg.setColor(selected ? SECONDARY_CONTAINER : 0x00000000);
        seg.setBackground(bg);
    }

    private void restyleChips(List<TextView> chips, String selected) {
        for (TextView chip : chips) {
            boolean on = selected.equals(chip.getTag());
            String label = catLabel((String) chip.getTag());
            chip.setText(on ? "✓  " + label : label);
            chip.setTextColor(on ? ON_SECONDARY_CONTAINER : ON_SURFACE);
            GradientDrawable bg = rounded(on ? SECONDARY_CONTAINER : 0x00000000, 16);
            bg.setStroke(dp(1), on ? SECONDARY_CONTAINER : OUTLINE_VAR);
            chip.setBackground(bg);
        }
    }
}
