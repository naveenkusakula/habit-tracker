package com.naveenkusakula.streaks;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * UI flow tests: the real MainActivity, tapped the way a user taps it.
 * "Clicking" a label finds the nearest clickable ancestor, exactly like a
 * finger landing on a non-clickable child inside a clickable row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class StreaksUiTest {

    /* ---------- view-tree helpers ---------- */

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static List<View> all(View root) {
        List<View> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static TextView findText(View root, String needle) {
        for (View v : all(root)) {
            if (v instanceof TextView && ((TextView) v).getText() != null
                && ((TextView) v).getText().toString().contains(needle)) {
                return (TextView) v;
            }
        }
        return null;
    }

    private static void tapText(View root, String needle) {
        TextView t = findText(root, needle);
        if (t == null) fail("No view with text containing: " + needle);
        View target = t;
        while (target != null && !target.hasOnClickListeners()) {
            if (target.getParent() instanceof View) target = (View) target.getParent();
            else break;
        }
        if (target == null || !target.hasOnClickListeners()) {
            fail("Nothing clickable for text: " + needle);
        }
        target.performClick();
        ShadowLooper.idleMainLooper();
    }

    private static View decor(MainActivity a) {
        return a.getWindow().getDecorView();
    }

    private static String prefs() {
        Context app = RuntimeEnvironment.getApplication();
        String s = app.getSharedPreferences("streaks_data", Context.MODE_PRIVATE)
            .getString("habits_json", "");
        return s == null ? "" : s;
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    /** Runs the full add-habit dialog the way a user would. */
    private static void addHabitViaDialog(MainActivity a, String name, boolean avoid) {
        tapText(decor(a), "New habit");
        ShadowLooper.idleMainLooper(); /* let the dialog's OnShowListener run */
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("add dialog should open", dialog);
        View dd = dialog.getWindow().getDecorView();
        EditText nameBox = null;
        for (View v : all(dd)) if (v instanceof EditText) nameBox = (EditText) v;
        assertNotNull("name field should exist", nameBox);
        nameBox.setText(name);
        if (avoid) tapText(dd, "Don't do it");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
    }

    /* ---------- tests ---------- */

    @Test
    public void smoke_emptyStateLaunches() {
        MainActivity a = Robolectric.setupActivity(MainActivity.class);
        assertNotNull(findText(decor(a), "No habits yet"));
        assertNotNull(findText(decor(a), "Do today"));
        assertNotNull(findText(decor(a), "Don't do"));
    }

    @Test
    public void doHabit_checkOff_updatesHeroAndPersists() {
        MainActivity a = Robolectric.setupActivity(MainActivity.class);
        addHabitViaDialog(a, "Read 10 pages", false);
        assertNotNull("habit row should appear", findText(decor(a), "Read 10 pages"));

        tapText(decor(a), "Read 10 pages"); /* row tap toggles done */
        assertNotNull("hero should show 100 after checking the only habit",
            findText(decor(a), "100"));
        assertTrue("completion for today should be saved",
            prefs().contains("\"" + today() + "\""));

        /* restart the activity: state must come back from storage */
        MainActivity a2 = Robolectric.setupActivity(MainActivity.class);
        assertNotNull(findText(decor(a2), "Read 10 pages"));
        assertNotNull("hero should still show 100 after restart", findText(decor(a2), "100"));
    }

    @Test
    public void dontHabit_breakIt_twoTapFlow_works() {
        MainActivity a = Robolectric.setupActivity(MainActivity.class);
        addHabitViaDialog(a, "No sugar", true);
        assertNotNull("avoid row should appear", findText(decor(a), "No sugar"));
        assertNotNull(findText(decor(a), "Broke it"));

        tapText(decor(a), "Broke it");
        assertNotNull("first tap must arm the button ('Tap again')",
            findText(decor(a), "Tap again"));

        tapText(decor(a), "Tap again");
        assertNotNull("after confirming, Undo should be offered",
            findText(decor(a), "Undo"));
        assertTrue("the break must be saved for today",
            prefs().contains("\"breaks\":[\"" + today() + "\"]"));

        tapText(decor(a), "Undo");
        assertNotNull("undo should restore the Broke it button",
            findText(decor(a), "Broke it"));
        assertTrue("the break must be removed from storage",
            !prefs().contains("\"breaks\":[\"" + today() + "\"]"));
    }

    @Test
    public void dontHabit_armExpiresAfterTimeout() {
        MainActivity a = Robolectric.setupActivity(MainActivity.class);
        addHabitViaDialog(a, "No soda", true);
        tapText(decor(a), "Broke it");
        assertNotNull(findText(decor(a), "Tap again"));
        /* let the 2.6 s confirm window lapse */
        Robolectric.getForegroundThreadScheduler()
            .advanceBy(3, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
        assertNotNull("after the timeout the button must disarm",
            findText(decor(a), "Broke it"));
        assertTrue("no break may be recorded by a timeout",
            !prefs().contains("\"breaks\":[\"" + today() + "\"]"));
    }

    @Test
    public void deleteHabit_longPressAndConfirm() {
        MainActivity a = Robolectric.setupActivity(MainActivity.class);
        addHabitViaDialog(a, "Temporary habit", false);
        assertNotNull(findText(decor(a), "Temporary habit"));

        TextView name = findText(decor(a), "Temporary habit");
        View target = name;
        boolean handled = false;
        while (target != null && !(handled = target.performLongClick())) {
            if (target.getParent() instanceof View) target = (View) target.getParent();
            else break;
        }
        assertTrue("long-press should be handled by the row", handled);
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("delete dialog should open", dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("habit row should be gone",
            findText(decor(a), "Temporary habit") == null);
        assertTrue("storage should no longer contain the habit",
            !prefs().contains("Temporary habit"));
    }
}
