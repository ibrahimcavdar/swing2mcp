# Writing tests

> **Experimental.** The test library is new; names may still change between releases.

The same agent that lets an AI tool use your application can run tests against it. A test is plain Java,
there is no MCP server and no AI tool involved when it runs, and the application is not changed.

```java
import io.github.ibrahimcavdar.swing2mcp.test.SwingApp;

try (SwingApp app = SwingApp.launch(Main.class)) {
    app.textField("User name").fill("admin");
    app.passwordField("Password").fill("admin");
    app.button("Log In").click();

    app.textField("Full Name").fill("Ali Veli");
    app.comboBox("City").select("İzmir");
    app.button("Save").click();

    app.label("Customer saved: Ali Veli").shouldExist();
    app.table().shouldHaveCell(3, "City", "İzmir");
}
```

- [Setup](#setup)
- [Starting the application](#starting-the-application)
- [Finding components](#finding-components)
- [Doing things](#doing-things)
- [Checking things](#checking-things)
- [Writing tests with an AI tool](#writing-tests-with-an-ai-tool)
- [Running on a build server](#running-on-a-build-server)
- [Limits](#limits)

## Setup

Add one test dependency. It needs Java 8 or newer and brings nothing else with it:

```xml
<dependency>
  <groupId>io.github.ibrahimcavdar</groupId>
  <artifactId>swing2mcp-test</artifactId>
  <version><!-- the swing2mcp version --></version>
  <scope>test</scope>
</dependency>
```

Use it with the test framework you already have (JUnit 4, JUnit 5, TestNG). A step that cannot be done
and a check that does not hold both throw an `AssertionError`.

## Starting the application

| | |
|---|---|
| `SwingApp.launch(Main.class, "arg")` | Starts the main class in a process of its own, with the Java and the class path that run the tests. |
| `SwingApp.launchCommand("bin/start.bat")` | Starts it with a command line, for applications started by a script or with their own Java. |

`close()` ends the application. It always runs in its own process: it can call `System.exit` without
taking the tests with it, and a test can start more than one.

`launchCommand` takes one string, as you would type it into a shell (`"java -jar C:/apps/demo.jar"`), and
runs it in the current directory, or in the directory given as a second argument. Put quotes around a
path with spaces, as in a shell.

Nothing has to wait for the application to come up: the first step of the test waits for its component,
also through a splash screen. If the start takes longer than 10 seconds, raise the timeout for that step
(`app.timeout(60000)`).

A test class usually keeps the application in a field:

```java
private SwingApp app;

@BeforeEach
void start() {
    app = SwingApp.launch(Main.class);
    app.textField("User name").fill("admin");
    app.passwordField("Password").fill("admin");
    app.button("Log In").click();
}

@AfterEach
void stop() {
    app.close();
}
```

This starts the application once per test method, which keeps the tests independent and costs its start
time each time. With `@BeforeAll` and `@AfterAll` it starts once per class, and the test methods share
its state.

## Finding components

There is one method per kind of component. The name is what a user would call the component: a button's
text, a field's label, a window's title.

```java
app.button("Save")
app.textField("Full Name")      // also passwordField, textArea, spinner
app.checkBox("Active customer") // also radioButton, toggleButton
app.comboBox("City")
app.table()                     // the only table on screen; table("Orders") if it has a label
app.list(), app.tree(), app.tabs()
app.label("Ready")
app.menuItem("Export to Excel")
```

Only `table()`, `list()`, `tree()` and `tabs()` can be asked for without a name. For any other component
that has no name, use its Java field (below).

Each method returns a type that offers only what that kind of component can do:

| Method | Returns | Besides `click()` and the common checks |
|---|---|---|
| `button`, `menuItem`, `label` | `Button`, `Locator` | |
| `checkBox`, `radioButton`, `toggleButton` | `Toggle` | `check()`, `uncheck()`, `isChecked()`, `shouldBeChecked()`, `shouldBeUnchecked()` |
| `textField`, `passwordField`, `textArea`, `spinner` | `TextInput` | `fill()`, `append()`, `fillAndSubmit()`, `value()`, `shouldHaveValue()` |
| `comboBox` | `ComboBox` | `select()`, `value()`, `shouldHaveValue()` |
| `list` | `ItemList` | `select()`, `itemCount()`, `selectedIndex()`, `read()`, `shouldContain()`, `shouldHaveSelectedIndex()` |
| `table` | `Table` | `selectRow()`, `doubleClickRow()`, `rowCount()`, `cell()`, `selectedRow()`, `read()`, `find()`, and the checks below |
| `tree` | `Tree` | `select()`, `expand()`, `collapse()`, `selectedPath()`, `read()` |
| `tabs` | `Tabs` | `select()` |
| `frame`, `dialog`, `internalFrame`, `group` | `Container` | every method of this table, limited to its inside; `answer()`, `shouldShow()` |
| `field`, `locator` | `AnyComponent` | `fill()`, `select()`, `value()`, `isChecked()`, `read()`, `shouldHaveValue()`, `shouldBeChecked()` |

The common checks are `exists()`, `isEnabled()`, `shouldExist()`, `shouldNotExist()`, `shouldBeEnabled()`
and `shouldBeDisabled()`.

**The whole name, or a pattern.** A `String` must be the whole name. A `java.util.regex.Pattern` must be
found somewhere in it:

```java
app.label(Pattern.compile("saved"))                          // contains
app.button(Pattern.compile("^save", Pattern.CASE_INSENSITIVE)) // starts with, any case
```

**Inside something.** When two components have the same name, say where:

```java
app.dialog("Confirm").button("OK")
app.group("Customer Details").textField("Email")   // a panel with a titled border
app.frame("Orders"), app.internalFrame("Invoice 42")
```

**By Java field.** A component without a usable name can be found by the field of your code that holds
it. This is the `field=` that a snapshot shows:

```java
app.field("txtEmail").fill("ali@example.com");
```

**Anything else** with a locator written the way a snapshot writes it:

```java
app.locator("slider \"Zoom\"")
app.locator("button \"...\" #2")   // the second of several, in snapshot order
```

A locator must match exactly one component. If it matches none, the error lists what is on screen
instead; if it matches several, the error lists them and says how to tell them apart:

```
Nothing on screen matches button "Sav" (still after waiting 10000 ms); button there: "Save", "Clear", "Delete"
```

To see what the application looks like to the library, print `app.snapshot()`.

## Doing things

Actions behave like a real user: real mouse and key events, so your listeners, validation and focus
handling run. They wait first until the component is on screen and usable (10 seconds by default,
`app.timeout(ms)` changes it), so tests need no sleeps. The window does not have to be in front.

```java
app.button("Save").click();                 // also doubleClick(), rightClick()
app.textField("Full Name").fill("Ali");     // also append(...), fillAndSubmit(...)
app.checkBox("Active customer").check();    // also uncheck()
app.comboBox("City").select("İzmir");
app.tabs().select("Orders");
app.list().select("SP-1002 Mehmet Demir");  // an item by its text
app.spinner("Quantity").fillAndSubmit("5"); // a spinner takes typed text over on Enter
app.table().selectRow("Ali Veli");          // a row by the text of one of its cells
app.tree().select("Europe/Turkey/Ankara");  // also expand(...), collapse(...)
app.selectMenu("File > Export > PDF");
app.pressKey("Ctrl+S");
```

Dialogs:

```java
app.button("Delete").clickAndAnswer("yes");   // answers the dialog that the click opens
app.answerDialog("ok");                       // answers the dialog that is open
app.dialog("Confirm").answer("cancel");
app.chooseFile(new File("data/customers.csv"));
```

`ok`, `yes`, `no` and `cancel` work in any language of the user interface; a button's own text works too.
These methods return what the dialog said (its title and message, as text), so a test can check it.

## Checking things

Methods that start with `should` wait until what they expect is true, and fail after the timeout with
what is there instead:

```java
app.label("Customer saved: Ali Veli").shouldExist();
app.dialog("Login").shouldNotExist();
app.button("Delete").shouldBeDisabled();
app.checkBox("Active customer").shouldBeChecked();
app.textField("Full Name").shouldHaveValue("");
app.comboBox("City").shouldHaveValue("İzmir");
app.table().shouldHaveRowCount(4);
app.table().shouldHaveCell(3, "City", "İzmir");   // the fourth row, the column with the header "City"
app.table().shouldContain("Ali Veli");
app.dialog("About").shouldShow("Version 3.2");
app.shouldShow("Ready");                          // anywhere on screen
```

Rows and columns count from 0, as in a snapshot. `shouldShow` and `shouldContain` look for the text as a
part of what is shown; the other checks compare the whole value.

A check that can never become true still waits for the whole timeout before it fails. That is the price
of not needing sleeps; lower `app.timeout(ms)` if failing fast matters more.

To compare yourself, read the value: `value()`, `isChecked()`, `isEnabled()`, `exists()`, `rowCount()`,
`cell(row, column)`, `selectedRow()`. These do not wait for a value to change, only for the component to
be on screen. The value of a text area with several lines has real line breaks in it.

`app.screenshotsOnFailure(new File("target/screenshots"))` saves a picture of the application whenever a
step or check fails; the error says where.

## Writing tests with an AI tool

The AI tool sees the application through the snapshot, and the snapshot uses the same words as the test
library: `button "Save"` in a snapshot is `app.button("Save")` in a test. So a good way to get a test is
to ask the AI tool to walk through a scenario in the running application and then write down what it did.

The AI tools also take a locator wherever they take a ref: `click` with `button "Save"` or
`field=btnSave` instead of `e24`. An AI tool that walks through the scenario this way has tried every
locator of the test in the real application before the test runs for the first time.

The test itself always starts its own copy of the application. The copy the AI tool is looking at stays
open next to it; if your application cannot run twice (it locks a file or a port), the AI tool has to
close its copy before it runs the test.

[skills/swing-ui-test/SKILL.md](../skills/swing-ui-test/SKILL.md) holds these instructions in a form an AI
tool can follow.

## Running on a build server

Swing needs a display; there is no headless mode for an application with windows. On Linux a virtual
display is enough:

```
xvfb-run -a mvn verify
```

The library does not use the real mouse or keyboard, so the window does not have to be in front and the
screen may be locked.

## Limits

- No multiple selection, drag and drop or typing into table cells yet; the AI tools have these.
- `launch(Main.class)` passes the class path on the command line. A very long class path can exceed the
  limit of Windows when tests are run from an IDE; Maven's test runner avoids this.
- Not tried: a display replacement such as Caciocavallo, Windows build servers that run as a service.
