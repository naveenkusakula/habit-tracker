package com.naveenkusakula.streaks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
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
 * Design: modelled directly on the Gradient Weather apps — one full-screen
 * sky gradient, a giant hairline hero number with a "condition" line,
 * content floating on the gradient in simple rows with hairline dividers.
 * No cards, no boxes.
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
 */
public class MainActivity extends Activity {

    private static final String PREFS = "streaks_data";
    private static final String KEY = "habits_json";

    private static final String[][] CATEGORIES = {
        {"health",  "Health",  "#34d399"},
        {"fitness", "Fitness", "#fb923c"},
        {"mind",    "Mind",    "#a78bfa"},
        {"work",    "Work",    "#38bdf8"},
        {"home",    "Home",    "#facc15"},
        {"money",   "Money",   "#4ade80"},
        {"other",   "Other",   "#cbd5e1"},
    };

    private static final int W = Color.WHITE;
    private static final int W70 = 0xB3FFFFFF;
    private static final int W60 = 0x99FFFFFF;
    private static final int W55 = 0x8CFFFFFF;
    private static final int W45 = 0x73FFFFFF;
    private static final int ROSE_TEXT = 0xFFFFC7D1;
    private static final int ROSE_NUM = 0xFFFDA4AF;

    private List<StreakLogic.HabitData> habits = new ArrayList<>();
    private FrameLayout root;
    private LinearLayout content;
    private final Handler handler = new Handler();
    private String lastRenderedDay = "";
    private String armedBreakId = null;

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

    private TextView sectionLabel(String text) {
        TextView t = tv(text, 12, 0xA6FFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.22f);
        return t;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        v.setLayoutParams(lp);
        return v;
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

    private String catLabel(String id) {
        for (String[] c : CATEGORIES) if (c[0].equals(id)) return c[1];
        return "Other";
    }

    private int catColor(String id) {
        for (String[] c : CATEGORIES) if (c[0].equals(id)) return Color.parseColor(c[2]);
        return Color.parseColor("#cbd5e1");
    }

    private String prettyDate(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso, StreakLogic.ISO);
            return d.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.getDayOfMonth();
        } catch (RuntimeException e) { return iso; }
    }

    /* The sky. Two states, like the weather apps: the everyday gradient,
       and a warmer golden one when the day is fully done ("all clear"). */
    private GradientDrawable sky(boolean allClear) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            allClear
                ? new int[]{0xFF2E6BD8, 0xFF2FA3A0, 0xFF8FBF7F, 0xFFF0C169, 0xFFF78D6B}
                : new int[]{0xFF2B4ACB, 0xFF5B4BC4, 0xFF8A55C0, 0xFFC65D8F, 0xFFF2A56B});
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
        w.setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        root = new FrameLayout(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(26), dp(64), dp(26), dp(120));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* Bottom-centre pill, the one control — like the weather apps' bar. */
        Button add = new Button(this);
        add.setText("+  Add habit");
        add.setTextSize(15);
        add.setTextColor(Color.WHITE);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setAllCaps(false);
        add.setMinHeight(0); add.setMinWidth(0);
        add.setPadding(dp(24), dp(13), dp(24), dp(13));
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(dp(30));
        pill.setColor(0x8C14102E);
        pill.setStroke(dp(1), 0x40FFFFFF);
        add.setBackground(pill);
        add.setElevation(dp(8));
        FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        addLp.bottomMargin = dp(28);
        add.setLayoutParams(addLp);
        add.setOnClickListener(v -> showAddDialog());
        root.addView(add);

        setContentView(root);
    }

    /* ---------------- rendering ---------------- */

    private void render() {
        LocalDate today = LocalDate.now();
        String todayS = StreakLogic.fmt(today);
        lastRenderedDay = todayS;
        armedBreakId = null;
        content.removeAllViews();

        List<StreakLogic.HabitData> dos = new ArrayList<>(), avoids = new ArrayList<>();
        for (StreakLogic.HabitData h : habits) (h.type.equals("do") ? dos : avoids).add(h);
        int done = 0;
        for (StreakLogic.HabitData h : dos) if (h.completions.contains(todayS)) done++;
        int total = dos.size();
        boolean allClear = total > 0 && done == total;
        int best = 0;
        for (StreakLogic.HabitData h : habits) {
            best = Math.max(best, h.type.equals("do")
                ? StreakLogic.doStreak(h.completions, today)
                : StreakLogic.avoidStreak(h.createdAt, h.breaks, today));
        }

        root.setBackground(sky(allClear));
        getWindow().setNavigationBarColor(allClear ? 0xFFE57A52 : 0xFFE08A54);

        /* brand + date, centred like a weather app's location line */
        TextView brand = tv("S T R E A K S", 14, 0xE6FFFFFF);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.CENTER);
        content.addView(brand);

        String dateLine = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault())
            + ", " + today.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
            + " " + today.getDayOfMonth();
        TextView dateTv = tv(dateLine, 15, W70);
        dateTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dLp = wrap();
        dLp.topMargin = dp(6);
        dLp.gravity = Gravity.CENTER_HORIZONTAL;
        dateTv.setLayoutParams(dLp);
        content.addView(dateTv);

        /* hero: the giant number, the "condition", the feels-like line */
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        LinearLayout.LayoutParams hLp = wrap();
        hLp.topMargin = dp(26);
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hero.setLayoutParams(hLp);
        TextView num = tv(total == 0 ? "–" : String.valueOf(StreakLogic.percent(done, total)), 118, W);
        num.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        num.setShadowLayer(dp(8), 0, dp(3), 0x26000000);
        hero.addView(num);
        if (total > 0) {
            TextView pct = tv("%", 42, W);
            pct.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            LinearLayout.LayoutParams pLp = wrap();
            pLp.topMargin = dp(16);
            pct.setLayoutParams(pLp);
            hero.addView(pct);
        }
        content.addView(hero);

        TextView cond = tv(StreakLogic.conditionText(done, total), 25, W);
        cond.setGravity(Gravity.CENTER);
        cond.setShadowLayer(dp(6), 0, dp(2), 0x26000000);
        LinearLayout.LayoutParams cLp = wrap();
        cLp.topMargin = dp(2);
        cLp.gravity = Gravity.CENTER_HORIZONTAL;
        cond.setLayoutParams(cLp);
        content.addView(cond);

        String sub = total == 0
            ? "Add a habit to start your day"
            : done + " of " + total + " done today" + (best > 0 ? "  ·  🔥 " + best + "-day streak" : "");
        TextView subTv = tv(sub, 15, W70);
        subTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = wrap();
        sLp.topMargin = dp(7);
        sLp.gravity = Gravity.CENTER_HORIZONTAL;
        subTv.setLayoutParams(sLp);
        content.addView(subTv);

        /* this week — the hourly-strip analog */
        if (!dos.isEmpty()) {
            addSectionGap(34);
            content.addView(hairline());
            addSectionGap(18);
            content.addView(sectionLabel("THIS WEEK"));
            content.addView(weekStrip(dos, today));
        }

        /* do today — the daily-forecast analog: plain rows, hairlines */
        addSectionGap(30);
        content.addView(hairline());
        addSectionGap(18);
        content.addView(sectionLabel("DO TODAY"));
        if (dos.isEmpty()) {
            content.addView(emptyLine("Nothing here yet — your first habit is one tap away."));
        } else {
            for (StreakLogic.HabitData h : dos) content.addView(doRow(h, today));
        }

        addSectionGap(26);
        content.addView(hairline());
        addSectionGap(18);
        content.addView(sectionLabel("DON'T DO"));
        if (avoids.isEmpty()) {
            content.addView(emptyLine("Nothing here yet — add something you're quitting."));
        } else {
            for (StreakLogic.HabitData h : avoids) content.addView(avoidRow(h, today));
        }

        TextView footer = tv("Your data never leaves this phone.", 12, W45);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = wrap();
        fLp.topMargin = dp(34);
        fLp.gravity = Gravity.CENTER_HORIZONTAL;
        footer.setLayoutParams(fLp);
        content.addView(footer);
    }

    private void addSectionGap(int dpVal) {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(dpVal)));
        content.addView(gap);
    }

    private TextView emptyLine(String text) {
        TextView t = tv(text, 14.5f, W55);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout weekStrip(List<StreakLogic.HabitData> dos, LocalDate today) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(14);
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
                12, isToday ? W : W70);
            if (isToday) letter.setTypeface(Typeface.DEFAULT_BOLD);
            cell.addView(letter);

            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            if (tracked == 0) {
                dotBg.setColor(0x00000000);
                dotBg.setStroke(dp(1), 0x4DFFFFFF);
            } else {
                int alpha = 70 + (int) Math.round(frac * 185);
                dotBg.setColor((alpha << 24) | 0xFFFFFF);
            }
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(11), dp(11));
            dotLp.topMargin = dp(8);
            dot.setLayoutParams(dotLp);
            cell.addView(dot);

            TextView pct = tv(tracked == 0 ? "–" : StreakLogic.percent(
                (int) Math.round(frac * tracked), tracked) + "%", 11, isToday ? W : W60);
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
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowLp);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        main.setPadding(0, dp(13), 0, dp(13));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(wrapW(1));
        texts.addView(tv(h.name, 18, W));
        String sub = catLabel(h.category)
            + (streak > 0 ? "  ·  🔥 " + streak + (streak == 1 ? " day" : " days")
               : done ? "  ·  Done today" : "  ·  Not done yet");
        TextView subTv = tv(sub, 12.5f, W60);
        LinearLayout.LayoutParams subLp = wrap();
        subLp.topMargin = dp(3);
        subTv.setLayoutParams(subLp);
        texts.addView(subTv);
        main.addView(texts);

        Button check = new Button(this);
        check.setText(done ? "✓" : "");
        check.setTextSize(20);
        check.setTextColor(0xFF6D4FC2);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setAllCaps(false);
        check.setMinHeight(0); check.setMinWidth(0);
        check.setPadding(0, 0, 0, 0);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        if (done) cg.setColor(W);
        else { cg.setColor(0x00000000); cg.setStroke(dp(1.5f), 0xCCFFFFFF); }
        check.setBackground(cg);
        LinearLayout.LayoutParams cgLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        cgLp.leftMargin = dp(14);
        check.setLayoutParams(cgLp);
        View.OnClickListener toggle = v -> {
            if (done) h.completions.remove(todayS); else h.completions.add(todayS);
            save();
            render();
        };
        check.setOnClickListener(toggle);
        main.addView(check);
        row.addView(main);
        row.setOnClickListener(toggle);
        row.setOnLongClickListener(v -> { confirmDelete(h); return true; });

        row.addView(hairline());
        return row;
    }

    private LinearLayout avoidRow(final StreakLogic.HabitData h, LocalDate today) {
        final String todayS = StreakLogic.fmt(today);
        final boolean broke = h.breaks.contains(todayS);
        final int streak = StreakLogic.avoidStreak(h.createdAt, h.breaks, today);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        main.setPadding(0, dp(13), 0, dp(13));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(wrapW(1));
        texts.addView(tv(h.name, 18, W));
        TextView subTv = tv(broke
            ? catLabel(h.category) + "  ·  Broken today — back to 0"
            : catLabel(h.category) + "  ·  since " + prettyDate(h.createdAt), 12.5f, W60);
        LinearLayout.LayoutParams subLp = wrap();
        subLp.topMargin = dp(3);
        subTv.setLayoutParams(subLp);
        texts.addView(subTv);

        final boolean armed = h.id.equals(armedBreakId);
        TextView action = tv(broke ? "Undo" : armed ? "Tap again — reset to 0" : "Broke it",
            14, broke ? W : ROSE_TEXT);
        if (armed) action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setPadding(0, dp(8), dp(4), dp(2));
        action.setOnClickListener(v -> {
            if (broke) {
                h.breaks.remove(todayS);
                save();
                render();
            } else if (armed) {
                if (!h.breaks.contains(todayS)) h.breaks.add(todayS);
                java.util.Collections.sort(h.breaks);
                save();
                render();
            } else {
                armedBreakId = h.id;
                render();
                handler.postDelayed(this::render, 2600);
            }
        });
        texts.addView(action);
        main.addView(texts);

        LinearLayout numCol = new LinearLayout(this);
        numCol.setOrientation(LinearLayout.VERTICAL);
        numCol.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams ncLp = wrap();
        ncLp.leftMargin = dp(14);
        numCol.setLayoutParams(ncLp);
        TextView num = tv(String.valueOf(streak), 32, broke ? ROSE_NUM : W);
        num.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        numCol.addView(num);
        TextView daysLabel = tv(streak == 1 ? "DAY" : "DAYS", 9.5f, W60);
        daysLabel.setLetterSpacing(0.14f);
        numCol.addView(daysLabel);
        main.addView(numCol);

        row.addView(main);
        row.setOnLongClickListener(v -> { confirmDelete(h); return true; });
        row.addView(hairline());
        return row;
    }

    /* ---------------- dialogs ---------------- */

    private void styleDialog(AlertDialog dialog) {
        Window dw = dialog.getWindow();
        if (dw != null) {
            GradientDrawable bg = sky(false);
            bg.setCornerRadius(dp(26));
            bg.setStroke(dp(1), 0x40FFFFFF);
            dw.setBackgroundDrawable(bg);
        }
        Button pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (pos != null) { pos.setTextColor(W); pos.setTypeface(Typeface.DEFAULT_BOLD); }
        Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (neg != null) neg.setTextColor(W70);
    }

    private void confirmDelete(final StreakLogic.HabitData h) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Delete habit?")
            .setMessage("“" + h.name + "” and its streak history will be gone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (d, which) -> {
                habits.remove(h);
                save();
                render();
            })
            .create();
        dialog.setOnShowListener(d -> styleDialog(dialog));
        dialog.show();
    }

    private void showAddDialog() {
        final String[] selType = {"do"};
        final String[] selCat = {"health"};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(8));

        TextView heading = tv("New habit", 21, W);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);

        final EditText name = new EditText(this);
        name.setHint("e.g. Read 10 pages");
        name.setHintTextColor(W45);
        name.setTextColor(W);
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setBackgroundColor(Color.TRANSPARENT);
        name.setPadding(0, dp(10), 0, dp(10));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(14);
        name.setLayoutParams(nameLp);
        box.addView(name);
        box.addView(hairline());

        TextView typeLabel = sectionLabel("TYPE");
        LinearLayout.LayoutParams tlLp = wrap();
        tlLp.topMargin = dp(22); tlLp.bottomMargin = dp(10);
        typeLabel.setLayoutParams(tlLp);
        box.addView(typeLabel);

        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout doOpt = typeOption("✓  Do it");
        final LinearLayout avoidOpt = typeOption("✕  Don't do it");
        segRow.addView(doOpt);
        segRow.addView(avoidOpt);
        box.addView(segRow);

        TextView catLabelTv = sectionLabel("CATEGORY");
        LinearLayout.LayoutParams clLp = wrap();
        clLp.topMargin = dp(22); clLp.bottomMargin = dp(4);
        catLabelTv.setLayoutParams(clLp);
        box.addView(catLabelTv);

        final List<LinearLayout> catRows = new ArrayList<>();
        for (String[] c : CATEGORIES) {
            final String catId = c[0];
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(9), 0, dp(9));
            r.setTag(catId);
            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(Color.parseColor(c[2]));
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotLp.rightMargin = dp(12);
            dot.setLayoutParams(dotLp);
            r.addView(dot);
            TextView lbl = tv(c[1], 15.5f, W70);
            lbl.setLayoutParams(wrapW(1));
            r.addView(lbl);
            TextView mark = tv("", 15, W);
            mark.setTag("mark");
            r.addView(mark);
            r.setOnClickListener(v -> { selCat[0] = catId; restyleCatRows(catRows, selCat[0]); });
            catRows.add(r);
            box.addView(r);
        }

        final Runnable restyleSeg = () -> {
            styleTypeOption(doOpt, selType[0].equals("do"));
            styleTypeOption(avoidOpt, selType[0].equals("avoid"));
        };
        doOpt.setOnClickListener(v -> { selType[0] = "do"; restyleSeg.run(); });
        avoidOpt.setOnClickListener(v -> { selType[0] = "avoid"; restyleSeg.run(); });
        restyleSeg.run();
        restyleCatRows(catRows, selCat[0]);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add habit", null)
            .create();
        dialog.setOnShowListener(d -> {
            styleDialog(dialog);
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

    private LinearLayout typeOption(String text) {
        LinearLayout opt = new LinearLayout(this);
        opt.setOrientation(LinearLayout.VERTICAL);
        opt.setGravity(Gravity.CENTER_HORIZONTAL);
        opt.setLayoutParams(wrapW(1));
        opt.setPadding(0, dp(6), 0, 0);
        TextView t = tv(text, 15.5f, W55);
        t.setTag("label");
        opt.addView(t);
        View bar = new View(this);
        bar.setTag("bar");
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(30), dp(2));
        barLp.topMargin = dp(7);
        bar.setLayoutParams(barLp);
        opt.addView(bar);
        return opt;
    }

    private void styleTypeOption(LinearLayout opt, boolean selected) {
        TextView t = (TextView) opt.findViewWithTag("label");
        View bar = opt.findViewWithTag("bar");
        t.setTextColor(selected ? W : W55);
        t.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        bar.setBackgroundColor(selected ? W : Color.TRANSPARENT);
    }

    private void restyleCatRows(List<LinearLayout> rows, String selected) {
        for (LinearLayout r : rows) {
            boolean on = selected.equals(r.getTag());
            TextView lbl = (TextView) r.getChildAt(1);
            TextView mark = (TextView) r.findViewWithTag("mark");
            lbl.setTextColor(on ? W : W70);
            lbl.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            mark.setText(on ? "✓" : "");
        }
    }
}
