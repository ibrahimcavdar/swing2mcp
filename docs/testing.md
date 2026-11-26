# Writing tests

> **Experimental.** The test library is new; its API may still change between releases.

A test written with `swing2mcp-test` starts your application, uses it like a user and checks what it
shows. The test is plain Java. No MCP server and no AI tool is involved when it runs, and the application
is not changed.

```java
import io.github.ibrahimcavdar.swing2mcp.test.SwingApp;

try (SwingApp app = SwingApp.launch(Main.class)) {
    app.textField("User name").fill("admin");
    app.passwordField("Password").fill("admin");
    app.button("Log In").click();

    app.textField("Full Name").fill("Jane Smith");
    app.comboBox("City").select("London");
    app.button("Save").click();

    app.label("Customer saved: Jane Smith").shouldExist();
    app.table().shouldHaveCell(3, "City", "London");   // the fourth row, the column "City"
}
```

- [Setup](#setup)
- [Starting the application](#starting-the-application)
- [Finding components](#finding-components)
- [Doing things](#doing-things)
- [Checking things](#checking-things)
- [When a test fails](#when-a-test-fails)
- [Less common components](#less-common-components)
- [Writing tests with an AI tool](#writing-tests-with-an-ai-tool)
- [Running on a build server](#running-on-a-build-server)
- [Limits](#limits)
- [All methods](#all-methods)

## Setup

Add one test dependency to the module that has your main class. It needs Java 8 or newer and brings
nothing else with it:

```xml
<dependency>
  <groupId>io.github.ibrahimcavdar</groupId>
  <artifactId>swing2mcp-test</artifactId>
  <version><!-- the swing2mcp version --></version>
  <scope>test</scope>
</dependency>
```

That is all: the part of swing2mcp that runs inside the application (the agent) is in this jar, and the
library starts your application with it. The Maven plugin and `swing2mcp:setup` are not needed for tests.

Use it with the test framework you already have (JUnit 4, JUnit 5, TestNG). A step that cannot be done
and a check that does not hold both throw an `AssertionError`.

## Starting the application

| | |
|---|---|
| `SwingApp.launch(Main.class, "arg")` | Starts the main class in a process of its own, with the Java and the class path that run the tests. The class can also be given by its name, as a `String`. |
| `SwingApp.launchCommand("start.bat")` | Starts it with a command line, for applications started by a script or with their own Java. |

`close()` ends the application. The application runs in a process of its own, so it can call
`System.exit` without taking the tests with it. To test the application's own way out,
`app.shouldHaveExited()` waits until it has ended, and `app.isRunning()` tells whether it still runs.

`launchCommand` takes one string, as you would type it into a shell (`"java -jar C:/apps/demo.jar"`), and
runs it in the current directory, or in the directory given as a second argument. Put quotes around a
path with spaces, as in a shell. The agent gets into the application through the environment variable
`JAVA_TOOL_OPTIONS`; a start script that sets this variable itself has to keep what is in it.

Nothing has to wait for the application to come up: the first step of the test waits for its component,
also through a splash screen. A step waits 10 seconds; for an application that takes longer to show its
first window, start with `SwingApp.launch(Main.class).timeout(60000)`.

A test class usually keeps the application in a field (JUnit 5 here; in JUnit 4 the annotations are
`@Before` and `@After`):

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
time each time. Started once per class instead (`@BeforeAll`, `@AfterAll`), the test methods share its
state.

## Finding components

The library sees the application as a snapshot: a text outline of what is on screen, the same one the AI
tools get. `System.out.println(app.snapshot())` prints it:

```
- frame "Customers" [ref=e1] active
  - textfield "Full Name" [ref=e7] value="" field=txtName
  - combobox "City" [ref=e9] value="London" field=cmbCity
  - button "Save" [ref=e12] field=btnSave
```

Each line is a component: its kind, its name in quotes, its state. The name is what a user would call
it: a button's text, a field's label, a window's title. A test finds a component by these two, with one
method per kind:

```java
app.button("Save");
app.textField("Full Name");       // also passwordField, textArea, spinner
app.checkBox("Active customer");  // also radioButton, toggleButton
app.comboBox("City");
app.table();                      // the only table on screen; table("Orders") if it has a label
app.list();                       // also tree(), tabs()
app.label("Ready");
app.menuItem("Export to Excel");
```

Only `table()`, `list()`, `tree()` and `tabs()` can be asked for without a name. Each method returns a
type that offers only what that kind of component can do; [All methods](#all-methods) lists them.

**The whole name, or a pattern.** A `String` must be the whole name. A `java.util.regex.Pattern` must be
found somewhere in it:

```java
app.label(Pattern.compile("saved"));                            // contains
app.button(Pattern.compile("^save", Pattern.CASE_INSENSITIVE)); // starts with, any case
```

**Inside something.** When two components have the same name, say where:

```java
app.dialog("Confirm").button("OK");
app.group("Customer Details").textField("Email");   // a panel with a titled border
app.frame("Orders").table();                        // also internalFrame("Invoice 42")
```

**By Java field.** A component without a usable name can be found by the field of your code that holds
it, the `field=` of its snapshot line:

```java
app.field("txtEmail").fill("jane@example.com");
```

**Anything else** with the snapshot line itself, without the `[ref=...]`. This is called a locator:

```java
app.locator("slider \"Zoom\"");
app.locator("button \"...\" #2");   // the second of several, in snapshot order
```

A table, tree, list or tabbed pane that is found by its field or by a locator keeps its own commands and
checks with `asTable()`, `asTree()`, `asList()` or `asTabs()`:

```java
app.field("tblJobs").asTable().fillCell(0, "Name", "Job 1");
```

What a test names must be exactly one component. If there is none or there are several, the step fails
and the error lists what is there (see [When a test fails](#when-a-test-fails)).

## Doing things

Clicks, typing and keys reach the application as the mouse and key events a user would cause, so your
listeners, validation and focus handling run. The events go straight into the application, not through
the mouse and keyboard of the operating system, so the window does not have to be in front. Picking
something (an option, a row, a tab, a tree node, a file) sets the selection directly.

Every action first waits until its component is on screen and usable, for 10 seconds at most. So tests
normally need no sleeps. `app.timeout(ms)` changes the 10 seconds for all steps that follow.

```java
app.button("Save").click();                 // also doubleClick(), rightClick(), hover()
app.textField("Full Name").fill("Jane");    // also append(...), fillAndSubmit(...)
app.checkBox("Active customer").check();    // also uncheck()
app.comboBox("City").select("London");
app.tabs().select("Orders");
app.list().select("SP-1002 John Brown");    // an item by its text
app.spinner("Quantity").fillAndSubmit("5"); // a spinner takes typed text over on Enter
app.table().selectRow("Jane Smith");        // a row by the text of one of its cells
app.tree().select("Europe/France/Paris");   // also expand(...), collapse(...)
app.selectMenu("File > Export > PDF");
app.pressKey("Ctrl+S");
```

The mouse goes to a point of the window, as a user's does. A component that is scrolled out of view is
scrolled into view first. If something else lies on top of the component at that point, the step fails
and says what covers it, instead of clicking through it.

Dialogs:

```java
app.button("Delete").clickAndAnswer("yes");   // answers the dialog that the click opens
app.answerDialog("ok");                       // answers the dialog that is open
app.answerDialogWith("Q3 report");            // types into an input dialog and presses OK
app.dialog("Confirm").answer("cancel");
app.chooseFile(new File("data/customers.csv"));   // Swing's file chooser
```

`ok`, `yes`, `no` and `cancel` find Swing's standard buttons in any language of the user interface; a
button's own text works too. These methods return a text that starts with what was answered, for a
message box with its message, so a test can check what the dialog asked.

Table cells are edited the way a user edits them. Rows and columns count from 0; a column can also be
given by its header text:

```java
app.table().fillCell(0, "Task", "Budget 2026");   // double-click, type, Enter
app.table().selectInCell(0, "Owner", "Alex");     // a cell that is edited with a combo box
app.table().clickCell(2, "Active");               // e.g. a check box in a cell
```

## Checking things

Methods that start with `should` wait until what they expect is true, and fail after the timeout with
what is there instead:

```java
app.label("Customer saved: Jane Smith").shouldExist();
app.dialog("Login").shouldNotExist();
app.button("Delete").shouldBeDisabled();
app.checkBox("Active customer").shouldBeChecked();
app.textField("Full Name").shouldHaveValue("");
app.comboBox("City").shouldHaveValue("London");
app.table().shouldHaveRowCount(4);
app.table().shouldHaveCell(3, "City", "London");   // or the column by its position: (3, 3, "London")
app.table().shouldContain("Jane Smith");
app.table().shouldHaveSelectedRow(3);
app.dialog("About").shouldShow("Version 3.2");
app.shouldShow("Ready");                           // anywhere on screen; also shouldNotShow(...)
```

`shouldShow` and `shouldContain` look for the text as a part of what is shown; the other checks compare
the whole value.

To compare yourself, read the value: `value()`, `isChecked()`, `isEnabled()`, `exists()`, `rowCount()`,
`cell(row, column)`, `selectedRow()`. These do not wait for a value to change, only for the component to
be on screen; `exists()` does not wait at all. The value of a text area with several lines has real line
breaks in it.

## When a test fails

The error says what was looked for and what is on screen instead, in the words of the snapshot:

```
Nothing on screen matches button "Sav" (still after waiting 10000 ms); button there: "Save", "Clear", "Delete"
```

A step or check that can never succeed still waits for the whole timeout before it fails. Lower
`app.timeout(ms)` if failing fast matters more.

`app.screenshotsOnFailure(new File("target/screenshots"))` saves a picture of the application whenever a
step or check fails; the error says where. `app.screenshot()` takes one at any time.

A step on an application that has ended fails and says so. If the application does not come up at all,
`launch` throws an `IllegalStateException` after 60 seconds, with the last lines the application printed.
Everything it prints goes to a file under `.swing2mcp/logs` in your home directory.

## Less common components

**A cell with another kind of editor.** While a cell is being edited, its editor is a component inside
the table and the snapshot lists it there. So it can be reached after `clickCell` or `doubleClickCell`:

```java
app.table("Orders").doubleClickCell(1, "Quantity");
app.locator("table \"Orders\" > spinner").fill("12");
```

**A tree node that is renamed in place** has its editor inside the tree in the same way. F2 opens it:

```java
app.tree().select("Europe/Holland");
app.tree().pressKey("F2");                          // the key goes to the tree, wherever the focus is
app.locator("tree > textfield").fill("Netherlands");
app.pressKey("Enter");
```

**A component that paints itself** (a drawing, a map, a chart) has nothing inside that could be named;
the snapshot shows it as `custom ... (custom-painted, use screenshot to see it)`. A test uses the mouse
at a point of it, in pixels from its top left corner, and checks the result in what the application
shows elsewhere or in a picture of the component:

```java
Locator map = app.field("mapCanvas");
map.clickAt(120, 80);            // also doubleClickAt(...), rightClickAt(...)
map.drag(200, 150, 260, 190);    // press at the first point, move, release at the second
map.hoverAt(120, 80);            // the mouse goes there without pressing; hover() for the center
map.scrollAt(120, 80, -3);       // the mouse wheel there: 3 notches up; scroll(3) for the center, down
byte[] png = map.screenshot();   // the component as it is painted now
```

## Writing tests with an AI tool

The AI tool sees the application through the same snapshot, so `button "Save"` there is
`app.button("Save")` in a test. A good way to get a test is to ask the AI tool to walk through a scenario
in the running application and then write down what it did.

The swing2mcp tools (`click`, `type_text` and the others) also take a locator wherever they take a ref:
`click` with `button "Save"` or `field=btnSave` instead of `e24`. An AI tool that walks through the
scenario this way has already tried the locators of the test in the real application.

The test itself always starts its own copy of the application. The copy the AI tool is looking at stays
open next to it; if your application cannot run twice (it locks a file or a port), the AI tool has to
close its copy before it runs the test.

[skills/swing-ui-test/SKILL.md](../skills/swing-ui-test/SKILL.md) holds these instructions in a form an AI
tool can follow. `mvn swing2mcp:setup` asks whether to add this skill to the AI tools it connects; then
"write a test for adding a customer" is enough.

## Running on a build server

Swing needs a display; there is no headless mode for an application with windows. On Linux a virtual
display is enough:

```
xvfb-run -a mvn verify
```

Because the events do not go through the operating system, the window does not have to be in front and
the screen may be locked.

## Limits

- No multiple selection and no drag and drop from one component to another yet; the swing2mcp tools for
  AI have these.
- The file dialog of the operating system (`java.awt.FileDialog`, which some applications open instead of
  Swing's file chooser) is not Swing. The snapshot says that it is open and
  `app.answerDialog("cancel")` closes it without a file, but a test cannot pick a file in it.
- The mouse of the test is not the mouse of the operating system. An application that asks the operating
  system where the mouse is (`MouseInfo`) gets the real one, and tooltips may not appear.
- `read()` and `shouldContain` look at the first 1000 rows of a table or items of a list.
- `launch(Main.class)` passes the class path on the command line. A very long class path can exceed the
  limit of Windows when tests are run from an IDE; Maven's test runner avoids this.
- Not tried: tests that run in parallel, a display replacement such as Caciocavallo, Windows build
  servers that run as a service.

## All methods

Every type except `Container` has `click()`, `doubleClick()`, `rightClick()`, `hover()`, `scroll()`,
`pressKey()`, `screenshot()` and the checks `exists()`, `isEnabled()`, `shouldExist()`, `shouldNotExist()`,
`shouldBeEnabled()`, `shouldBeDisabled()`.

| Method | Returns | It adds |
|---|---|---|
| `button`, `menuItem`, `label` | `Button`, `Locator` | |
| `checkBox`, `radioButton`, `toggleButton` | `Toggle` | `check()`, `uncheck()`, `isChecked()`, `shouldBeChecked()`, `shouldBeUnchecked()` |
| `textField`, `passwordField`, `textArea`, `spinner` | `TextInput` | `fill()`, `append()`, `fillAndSubmit()`, `value()`, `shouldHaveValue()` |
| `comboBox` | `ComboBox` | `select()`, `value()`, `shouldHaveValue()` |
| `list` | `ItemList` | `select()`, `itemCount()`, `selectedIndex()`, `read()`, `shouldContain()`, `shouldHaveSelectedIndex()` |
| `table` | `Table` | `selectRow()`, `doubleClickRow()`, `clickCell()`, `doubleClickCell()`, `fillCell()`, `selectInCell()`, `rowCount()`, `cell()`, `selectedRow()`, `read()`, `find()`, `shouldHaveRowCount()`, `shouldHaveCell()`, `shouldHaveSelectedRow()`, `shouldContain()` |
| `tree` | `Tree` | `select()`, `expand()`, `collapse()`, `selectedPath()`, `read()` |
| `tabs` | `Tabs` | `select()` |
| `frame`, `dialog`, `internalFrame`, `group` | `Container` | the methods of this table for what is inside it; `answer()`, `shouldShow()`, `exists()`, `shouldExist()`, `shouldNotExist()`, `screenshot()` |
| `field`, `locator` | `AnyComponent` | `fill()`, `select()`, `value()`, `isChecked()`, `read()`, `shouldHaveValue()`, `shouldBeChecked()`, `shouldBeUnchecked()`, `asTable()`, `asTree()`, `asList()`, `asTabs()` |
