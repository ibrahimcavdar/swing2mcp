---
name: swing-ui-test
description: Write a repeatable UI test for a Java Swing application with the swing2mcp test library, by walking through the scenario in the running application first. Use when asked to write, record or fix a UI test or regression test for a Swing screen.
---

# Writing a Swing UI test with swing2mcp

You have two things that speak the same language:

- the **swing2mcp MCP tools** (`snapshot`, `click`, `type_text`...), to use the running application;
- the **swing2mcp test library** (`swing2mcp-test`), to write down the same steps as a Java test that
  runs later without you.

A snapshot line `button "Save" [ref=e24] field=btnSave` becomes `app.button("Save")` in a test. Refs
(`e24`) only live for one run of the application: never put one into a test.

## Steps

1. **Check the project.** The test dependency must be there; add it if it is not, with the same version
   as the swing2mcp plugin in the pom:

   ```xml
   <dependency>
     <groupId>io.github.ibrahimcavdar</groupId>
     <artifactId>swing2mcp-test</artifactId>
     <version>...</version>
     <scope>test</scope>
   </dependency>
   ```

   Use the test framework and the folder the project already uses for tests.

2. **Walk through the scenario with the MCP tools.** Connect to the open application or start it, then
   do each step once by hand. After every step read the result: note the role and name of each
   component you used, and what on screen proves that the step worked (a label, a table row, a dialog).

3. **Write the test** from your notes, one line per step and one check per proof:

   ```java
   try (SwingApp app = SwingApp.launch(Main.class)) {
       app.textField("Full Name").fill("Ali Veli");
       app.button("Save").click();
       app.label("Customer saved: Ali Veli").shouldExist();
   }
   ```

4. **Run it against the open application** while you are still changing it. This takes seconds and
   leaves the application open:

   ```
   mvn test -Dtest=CustomerTest -Dswing2mcp.connect=running
   ```

   The test meets the application as it is now. If it is already logged in, the login steps of the test
   will fail: bring the application to the state the test expects first (log out, or restart it).

5. **Finish with one run from scratch**, without `-Dswing2mcp.connect`. Only this run counts: the test
   must pass on a freshly started application.

## Choosing how to find a component

Use the first of these that names exactly one component:

1. Role and name: `app.button("Save")`, `app.textField("Full Name")`, `app.comboBox("City")`.
2. The same inside a container, when the name appears twice:
   `app.dialog("Confirm").button("OK")`, `app.group("Customer Details").textField("Email")`.
3. The Java field, when there is no name or the name changes: `app.field("txtEmail")` for a snapshot
   line with `field=txtEmail`.
4. A pattern, for text with a changing part: `app.label(Pattern.compile("^Customer saved"))`.
5. A position, as the last resort: `app.locator("button \"...\" #2")`.

Prefer what a user sees (1, 2) over what a programmer sees (3): the test then also says what the screen
should look like.

## Rules

- **No sleeps.** Actions wait for their component; checks that start with `should` wait for what they
  expect. If something needs longer than 10 seconds, raise `app.timeout(ms)` for that step.
- **Check what the user would check.** After an action, assert the visible result
  (`shouldExist`, `shouldHaveValue`, `shouldHaveCell`, `shouldContain`, `shouldShow`), not just that the
  click did not fail.
- **Keep setup apart.** Put starting the application and logging in into the setup method of the test
  class, so that each test method is one scenario.
- **Do not depend on leftovers.** A test must not need data that an earlier manual step created. If the
  scenario needs a customer, the test creates it.
- **When a test fails, read the message.** It says what is on screen instead of what was expected, in
  snapshot form. Fix the locator or the expectation from that; do not guess.
- **Do not change the application to make a test pass** unless that is the task.

## Reference

Everything the library offers, with examples: `docs/testing.md` in the swing2mcp repository
(https://github.com/ibrahimcavdar/swing2mcp/blob/main/docs/testing.md).
