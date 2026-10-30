# swing2mcp

A developer tool that lets AI tools **see and use** legacy Java Swing applications without
touching their code. It offers a Playwright-like experience for Swing.
It works with Claude Code, Cursor, OpenCode, Codex and any tool that supports MCP.

## Quick start (Maven project)

**1. Add the plugin to your project's `pom.xml`.** This is the only part you need to add:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.swing2mcp</groupId>
      <artifactId>swing2mcp-maven-plugin</artifactId>
      <version>0.1.0-SNAPSHOT</version>
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

If the application runs from a jar, pass the folder of the compiled classes: `classesDir=target/classes`.

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
| `click` | Button, menu item, table row; double click, right click |
| `type_text` | Text field, password, spinner, editable combo |
| `select_option` | Combo, list, tab, table row, tree node |
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

## How does it work?

```
AI tool ──MCP──▶ swing2mcp MCP server ──localhost──▶ agent (inside the application)
```

The agent is added to the application's JVM as a `-javaagent`. It listens only on `127.0.0.1`, with a random
port and token. It writes the connection details to `~/.swing2mcp/jvms/<pid>.properties`
(readable only by its owner) and deletes the file when the application exits.

## Developing this repository

`mvn install` with JDK 17+ and Maven. Modules: `agent` (Java 8), `mcp` (Java 17),
`maven-plugin` (Java 8), `demo-app` and `demo-spring` (Java 8; login `admin` / `admin`). To try the demo
application, run `mvn swing2mcp:setup` and `mvn swing2mcp:run` in the `demo-app` folder.

Design decisions and roadmap: [DESIGN.md](DESIGN.md).
