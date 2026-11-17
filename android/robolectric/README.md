# UI tests (Robolectric)

These tests run the **real** `MainActivity` on the JVM — no emulator —
and tap through the actual flows the way a user does:

- empty-state launch
- add a Do habit in the dialog, check it off, hero shows 100%, restart
  the activity and the state is still there (persistence)
- add a Don't habit, tap **Broke it** (arms), tap again (break saved),
  **Undo** (break removed) — this is the regression test for the v1.1 bug
  where `render()` cleared the armed state and Broke it could never fire
- the 2.6 s confirm window expiring on its own, recording nothing
- delete a habit via long-press + confirm dialog

## Running

```sh
mvn -o test    # offline; dependencies must be in the local repo
```

Notes for this workspace:

- Dependencies (JUnit + Robolectric + the SDK 35 `android-all-instrumented`
  runtime) live in the local Maven repo. On this machine they were seeded
  with `~/android-tools/m2fetch.py` because Java's HTTP stacks can't use
  the VM's egress proxy; on a normal machine plain `mvn test` works.
- `libs/monitor-classes.jar` and `libs/espresso-classes.jar` are the
  `classes.jar` extracted from the AndroidX `monitor` and
  `espresso-idling-resource` AARs — a plain JVM can't load classes from an
  `.aar`, so the POM puts the extracted jars on the test classpath. They
  are test-only fixtures, never part of the app.
- Everything here is test tooling. The app itself still has **zero**
  dependencies.
