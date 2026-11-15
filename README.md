# swing2mcp

A developer tool that lets AI tools **see and use** legacy Java Swing applications without
touching their code. It offers a Playwright-like experience for Swing.
It works with Claude Code, Cursor, OpenCode, Codex and any tool that supports MCP.

> **Alpha.** swing2mcp is not on Maven Central yet. Build it once from source (JDK 17+, Maven 3.6.3+);
> this puts the plugin into your local Maven repository:
>
> ```bash
> git clone --branch v0.1.0 https://github.com/ibrahimcavdar/swing2mcp.git
> cd swing2mcp
> mvn install -Drevision=0.1.0 -DskipTests
> ```
>
> Releases are listed on the GitHub releases page. A build without `-Drevision` is the development
> version `999-SNAPSHOT`.

## Quick start (Maven project)

**1. Add the plugin to your project's `pom.xml`.** This is the only part you need to add:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.swing2mcp</groupId>
      <artifactId>swing2mcp-maven-plugin</artifactId>
      <version>0.1.0</version>
    </plugin>
  </plugins>
</build>
```

**2. Connect your AI tool.** Each developer runs this once:

```bash
mvn swing2mcp:setup
```

This command installs the MCP server under `~/.swing2mcp/bin` and writes the Claude Code (`.mcp.json`),
Cursor (`.cursor/mcp.json`), OpenCode (`opencode.json`) and Codex (`.codex/config.toml`; Codex reads it
only in projects marked as trusted) settings into the project. Existing settings
are kept. If you only want one of them: `-Dswing2mcp.clients=cursor`. These files contain machine-specific
paths; do not commit them to the repository.

**3. Run the application:**

```bash
mvn swing2mcp:run
```

The project is compiled and the application opens as usual. The only difference is that the AI tool can
connect to the application. When the application closes or Maven is stopped, everything shuts down. The
project's jar and code do not change.

**4. Tell the AI tool what you want it to do**, for example: *"Log in with admin/admin,
add a customer named Ali Veli from İzmir and check that it shows up in the table."*

### Connecting to an application that is already open

Even if the application was opened without swing2mcp (from the IDE, with `java -jar`, with a `.bat` script), the AI
tool can connect to it later: just say *"connect to the open application"*. `list_apps`
shows the Java applications on the machine. `connect_app` places the agent inside the application
without a restart; the logged-in session and open screens are kept. `setup` is enough for this;
you do not even need to add the plugin to the pom.

### Seeing code changes without a restart (hot reload)

The AI tool changes the source code, compiles it (`mvn -q compile` or the IDE) and with `hot_reload` applies the change
to the running application. The application does not close and the logged-in session is kept:

- If only button behavior, business logic or painting code changed, the open screens stay as they are
  (including typed text and selections).
- If a screen's setup code changed (constructor, `initComponents`, `@PostConstruct`...), only that
  screen is rebuilt.
- Adding a new method/field or a new lambda requires a restart on a standard JVM; the tool says so clearly
  without applying anything.

Only classes whose code really changed are reloaded, even when the build rewrote every class file.

**Multi-module projects:** when the application is started from one module (e.g. the one with the main
class), the other modules come from their jars in `~/.m2`. Put their compiled classes first on the
classpath, and `hot_reload` picks up changes in those modules too:

```
mvn swing2mcp:run -Dswing2mcp.classesDirs=../core/target/classes,../ui/target/classes
```

Without it, or if the application runs from a jar, pass the folder of the compiled classes instead:
`classesDir=../core/target/classes`. The classes as they were loaded are read from the jar, so the result is the
same: only the changed classes are reloaded.

### Spring applications

No extra setting is needed. If the application uses Spring, swing2mcp finds the Spring context itself
without touching the application. The AI tool can list screens and services with `spring_beans`, and
open a screen directly with `open_spring_bean`, without navigating the menus (*"open the Settings
screen"*). In applications without Spring these two tools just say "Spring not found"; everything else
works the same.

### `swing2mcp:run` options

| Property | Description |
|---|---|
| `-Dswing2mcp.mainClass=...` | Main class. If not given, it is taken from the jar/assembly/exec plugin settings. |
| `-Dswing2mcp.jar=target/app.jar` | Runs a prebuilt jar instead of the project classpath. |
| `-Dswing2mcp.javaHome=/jdk8` | Runs the application with a different Java (e.g. Maven on 17, the application on 8). |
| `-Dswing2mcp.jvmArgs="-Xmx1g -Dprofile=dev"` | Extra JVM options. |
| `-Dswing2mcp.args="..."` | Application arguments. |
| `-Dswing2mcp.classesDirs=../core/target/classes,...` | Class folders of other modules, put before the jars (multi-module hot reload). |
| `-Dswing2mcp.agent=false` | Runs plainly, without the agent. |

### Requirements

- Application: Java 8+ (tested with 8 and 21).
- Maven: 3.6.3+, can also run on Java 8.
- MCP server: Java 17+. If Maven runs on Java 8:
  `mvn swing2mcp:setup -Dswing2mcp.mcpJavaHome=/path/to/jdk17`

## What the AI tool can use

| Tool | Description |
|---|---|
| `list_apps` / `connect_app` | Lists running Java applications / connects to one; if it has no agent, places it without a restart |
| `snapshot` | Text summary of the windows, with `ref` IDs |
| `screenshot` | PNG image of a window or component |
| `click` | Real mouse click on a button, table cell, list item or tree node (by text or path); double click, right click, column header, with Ctrl/Shift |
| `type_text` | Types real keys into a text field, password, spinner or editable combo |
| `press_key` | Enter, Escape, Tab, arrows, F-keys and shortcuts such as Ctrl+S |
| `select_option` | Combo, list, tab, table cell, tree node (by path); several rows, items or nodes with Ctrl/Shift clicks, ranges included |
| `drag` | Mouse drag (pan a map, move a slider, a split pane divider, a table column) and drag and drop between components |
| `read_items` | Reads tables, lists, trees and combos page by page, as they are shown |
| `expand_node` | Expands or collapses a tree node by its path |
| `select_menu` | Chooses a menu path such as "File > Open", also in context menus |
| `handle_dialog` | Answers a message box, input dialog or file chooser |
| `wait_for` | Waits for a text to appear or disappear |
| `hot_reload` | Applies compiled code changes without a restart; rebuilds the screen if needed |
| `spring_beans` | Lists the beans in Spring applications (screens are shown separately) |
| `open_spring_bean` | Opens a Spring screen bean directly, without navigating the menu |
| `launch_app` / `stop_app` | The AI tool starting / closing the application itself (use outside Maven) |

Example snapshot:

```
- frame "ACME Customer Manager 3.2 - admin" [ref=e10] 900x620 active class=MainFrame
  - menubar [ref=e11]
    - menu "File" [ref=e12] items=3 (closed)
  - tabs [ref=e15] tabs=["Customers"(selected), "Orders", "Reports"] field=tabs
    - panel CustomerPanel [ref=e16] field=customerPanel
      - group "Customer Details" [ref=e17]
        - textfield "Full Name" [ref=e18] value="" field=txtName
        - combobox "City" [ref=e20] value="Ankara" options=["Ankara", "İstanbul", ...] field=cmbCity
        - button "Save" [ref=e23] field=btnSave
      - table [ref=e26] rows=3 cols=5 field=tblCustomers
        - columns: ID | Full Name | Email | City | Active
        - row 0: 1 | Ayşe Yılmaz | ayse@example.com | Ankara | [x]
```

`field=` is the name of the Java field that holds the component in the application; it makes matching the screen with the code easier.

Actions work **like a real user**, so the application runs the same code as for a person: `type_text` and
`press_key` send real key events (key listeners, input verifiers, length limits and shortcuts run),
`click` sends real mouse events (the focus moves first, as with a mouse), and menus open level by level.
The application window does not have to be in front: keys go to the application, not to the program you
are typing in. `drag` pans and moves with in-application mouse events, and does drag and drop through the
application's own TransferHandlers (its `canImport`, `importData` and `exportDone` run, at a real drop
location), without the operating system's drag and drop; `mode=os` uses the real mouse when that is
needed. An action that opens a dialog can answer it right away, e.g. `click` on Delete with
`dialog=yes`; the result repeats the dialog's message.

Actions first wait until the component can take input: visible,
enabled, editable, not blocked by a modal dialog or a busy overlay (up to `timeoutMs`, default 10 s). If
the application throws an exception that it does not handle, during the action or since the last call,
the tool result says so, with the stack trace, so the AI tool notices that its change broke something.

## How does it work?

```
AI tool ──MCP──▶ swing2mcp MCP server ──localhost──▶ agent (inside the application)
```

The agent is added to the application's JVM as a `-javaagent`. It listens only on `127.0.0.1`, with a random
port and token. It writes the connection details to `~/.swing2mcp/jvms/<pid>.properties`
(readable only by its owner) and deletes the file when the application exits.

## Developing this repository

`mvn install` with JDK 17+ and Maven. The version in the poms is always `999-SNAPSHOT` (`${revision}`);
a release is only a tag `vX.Y.Z`, which the release workflow builds with `-Drevision=X.Y.Z` and publishes
as a GitHub release. No commit ever changes a version. Modules: `agent` (Java 8), `mcp` (Java 17),
`maven-plugin` (Java 8), `demo-app` and `demo-spring` (Java 8; login `admin` / `admin`). To try the demo
application, run `mvn swing2mcp:setup` and `mvn swing2mcp:run` in the `demo-app` folder.

Design decisions and roadmap: [DESIGN.md](DESIGN.md).
