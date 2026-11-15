# swing2mcp — Design Notes

A developer tool that lets AI (Claude Code etc.) see and use legacy Java Swing applications
over MCP, without touching the product's code.

## Problem

- The feedback loop in Swing applications is very long: a restart takes 1+ minute
  (Spring beans, login, etc.).
- There is no Playwright-like experience as on the web: connecting to an open session to read the screen,
  pressing a button and verifying quickly is not possible.
- For non-developers such as designers and PMs, trying out the product is also slow and tedious.

## Terminology

- **Java agent:** A JVM concept, unrelated to an AI "agent". A jar that is loaded into the JVM
  with `-javaagent:x.jar` or at runtime (Attach API) and runs inside the
  application. The product's code, build and dependencies do not change; once the flag is removed, no trace
  remains.
- **Bridge (MCP server):** The process Claude Code talks to. It starts the application, places the agent
  inside it and forwards MCP calls to the agent.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **No** external visual approach (screenshot + OCR + mouse) | Claude/Codex computer use already does that; it adds nothing Java-specific. |
| D2 | The MCP protocol will **not be written** inside the agent | Writing MCP by hand in Java 8 is not wanted. |
| D3 | Architecture: **Java agent (inside the application) + bridge (outside)** | The agent stays small and Java 8 compatible; the bridge is written with modern tools. |
| D4 | **Single distribution**: the bridge jar carries the agent jar and injects it itself | The user installs/runs only one thing (`claude mcp add ...`). The burden of managing two parts does not fall on the user. |
| D5 | Bridge language: **Java 17+** (official MCP Java SDK) | The Attach API is only reachable from Java; with Node/Python a Java helper would still be needed (3 parts). The target audience (Java developers) already has a JDK. |
| D6 | Agent: **Java 8 bytecode, zero dependencies** (the JDK's own `HttpServer`, small hand-written JSON) | Zero risk of version conflicts with the product's libraries; runs on all of Java 8–21. |
| D7 | The agent uses only **public Swing/AWT API**, no reflection into JDK internal classes | The reflection problems seen in Java 8→21 migrations do not affect us. The agent runs on whatever JVM the application runs on; we do not change the JVM version. |
| D8 | Test application: **Java 8**, legacy-style demo application (a Java 21 variant later) | Realistic target environment. |
| D9 | Target platform: **Windows** (Linux later). Development is on macOS for now. | Windows-specific topics (DPI scaling, Robot behavior) are postponed; development continues on Windows. |
| D10 | Intended to be open source | Many companies have a similar problem. |
| D11 | Build integration first for **Maven** (plugin); Gradle/Ant etc. later or from the community | Legacy projects at work are mostly Maven. The build-system-specific part is kept thin; the engine (agent + MCP) stays independent of the build system. |
| D12 | Layered delivery: engine (agent) + delivery (Maven plugin, attach, launch) + connection (standard MCP, client-independent) | Answer to C1: the capability lives in the agent, the "developer tool" feel in the delivery layer. |
| D13 | The **only delivery the user sees: the Maven plugin**. The agent and the MCP server come automatically as dependencies of the plugin; all in one repository | The number of parts to deliver should be small (easy to explain and to get adopted). |
| D14 | Connecting the AI tool: `mvn swing2mcp:setup` installs the MCP server under `~/.swing2mcp/bin` and writes the Claude Code (`.mcp.json`), Cursor (`.cursor/mcp.json`), OpenCode (`opencode.json`) and Codex (`.codex/config.toml`) settings. By default all four are written; `-Dswing2mcp.clients=` narrows this down. Existing settings are kept | Client-independent and easy connection (C1). Since the settings contain machine-specific paths, each developer runs `setup` once. |
| D15 | The plugin is compiled with Java 8; it can be used even if Maven runs on Java 8. The MCP server needs Java 17; if Maven is on 8, `-Dswing2mcp.mcpJavaHome` is required | In legacy projects Maven often runs on Java 8. |
| D16 | `swing2mcp:run` compiles first, opens the application with the project's classpath in a separate process and waits until it closes (when Maven exits, the application closes too). `mainClass` is found automatically from the settings of other plugins (jar, assembly, exec). `-Dswing2mcp.jar` uses a prebuilt jar, `-Dswing2mcp.javaHome` a different Java. Long classpaths are written to a manifest jar so they do not hit the Windows command-line limit | The project's jar and code do not change; the agent exists only in this run. |
| D17 | The agent is placed into an application that does not have it only via `connect_app`, when the pid is given explicitly. Automatic connection (when there is only one application) applies only to applications that already have the agent | Placing something into an application must be an explicit request; no invisible intervention (the security sensitivity in C1/C2). |
| D18 | The **agent finds the Spring context itself**, without touching the application: (1) static fields in application classes ("SpringContext holder"), (2) the JVM shutdown hook (`registerShutdownHook`), (3) the object graph from the visible windows. If it is not found, the Spring tools fail politely and the other tools are not affected. No dependency route for now | Zero touch on the product, works with attach too, no new delivery part (D13). Spring is not referenced at compile time; works with every Spring version and without Spring. |
| D19 | Hot reload does not compile: the AI tool or the IDE compiles (`mvn -q compile`), `hot_reload` only applies compiled classes | Stays independent of the build system (D11); Maven, Gradle and IDE builds work the same way. |
| D20 | Changes are detected **at method level** (bytecode digest; the constant pool and line numbers do not affect it). A screen is rebuilt only if its setup code changed (the constructor, the init method and what they call, the constructors of anonymous classes they create); otherwise the screen state is kept | So that data typed in during the AI's "fix the bug → try again" loop is not lost. |
| D21 | Rebuild order: (1) in place, by calling the class's init method again (`initComponents`, `jbInit`, `$$$setupUI$$$`, `@PostConstruct`...), (2) if there is no init method, a new instance via the no-argument constructor and updating the field references in the parent components, (3) if neither exists, a "reopen it/restart" message | Rebuilding in place does not replace the object, so references elsewhere (Spring included) do not break. |
| D22 | Structural changes (adding/removing methods or fields, new lambdas, a changed superclass) are rejected on a standard JVM **without applying anything**, and the change is explained; if JetBrains Runtime (`-XX:+AllowEnhancedClassRedefinition`) or DCEVM is detected, they are applied. All classes are applied in one go (all or nothing) | Half-applied code leads to inconsistent behavior; the AI must know what to do (rewrite / restart). |
| D23 | When hot reload has no recorded version of a loaded class, it reads the class **as it was loaded, from its code source** (jar entry, or a class file older than the JVM). A directory that is not on the classpath is never taken as the baseline. In multi-module projects `run` takes an **explicit** `-Dswing2mcp.classesDirs` list that goes before the jars; sibling modules are not guessed | A build rewrites every class file of a module; without a baseline everything looked changed (all loaded classes reloaded, unchanged screens rebuilt). Guessing other modules' folders could pick the wrong ones; an explicit list is visible and predictable. |
| D24 | Actions **wait first** (default 10 s, `timeoutMs`) until the component is showing, enabled, editable where needed, not blocked by a modal dialog or a busy glass pane, and the UI thread answers. A glass pane counts as busy if it is visible over the component and has a wait cursor or mouse listeners (internal frames: only a wait cursor, since Swing gives their glass pane a listener). The result says how long it waited and why | Right after `wait_for` finds a field, the screen may still be blocked (e.g. a loading dialog). Playwright-style actionability waits remove that race without extra calls from the AI. |
| D25 | Uncaught exceptions are **reported in the next tool result** (actions, snapshot, wait_for, errors), each once, with a short stack trace. The agent wraps the default uncaught exception handler and passes every exception on (to the previous handler, or prints it like the JVM); a handler that the application installs later, or sets on the UI thread, is wrapped on the next request. An exception thrown inside the action itself (e.g. a button's listener) is reported as "Action failed". Log output is not captured | The AI must notice that its change broke something; the application's own error handling must stay as it was. Logging frameworks differ too much to capture without dependencies. |
| D26 | Snapshot: refs are assigned by **identity**, not `equals`; a `Window` whose children belong to another container (a framework's window facade for an internal frame) is skipped; a label subclass used as a field's name is hidden like a plain `JLabel`; a custom-painted leaf component shows the text of a public `getText()`/`getLabel()`/`getTitle()` found by reflection | Seen in a large legacy application whose framework has such facades (with `equals`/`hashCode` delegated) and its own label class; the output got twice as long and less clear. Reflection keeps the agent free of compile-time dependencies. |
| D27 | Keys are **real key events inside the JVM**, dispatched to the component that has the keyboard focus, through the KeyboardFocusManager (key event dispatchers, focus traversal, key bindings, menu shortcuts, the default button). The window is never brought to the front. In an inactive window AWT has no focus owner, so the window's most recent focus owner takes the keys, and the focus events of a focus change are delivered with `KeyboardFocusManager.redispatchEvent`, as AWT does in the active window | The developer usually types in another program while the AI works; keys through the operating system would need the window in front and could end up in the wrong program. Public API only (D7); focus-lost handlers such as a formatted field's commit still run |
| D28 | `type_text` types like a user: it focuses the field (the previous field's input verifier runs; if it refuses, nothing is typed and the result says why), selects the old text and sends each character as key pressed/typed/released. `mode=set` keeps the old `setText`; `mode=os` uses `java.awt.Robot`, checks before every key that the window still has the focus, and fails if the keys never arrived (e.g. a locked screen). `press_key` takes keys and chords ("Enter", "Ctrl+S", "Shift+Tab", "Tab Tab Enter"); Alt+F4 becomes the window-closing event that the operating system would send | Key listeners, document filters, length limits and input verifiers must run as they do for a user; `setText` skipped key listeners. The result reports when the application changed or rejected keys |
| D29 | Clicks are **real mouse press and release**, also for buttons (no `doClick`): the press moves the focus first, so the previous field's input verifier and focus-lost handlers run before the release fires the action. If a verifier keeps the focus, the result says so (as in Swing, the button's action still runs) | With real typing, formatted fields commit when the focus leaves them; `doClick` did not move the focus, so a Save button could read an uncommitted value |
| D30 | An action is a **list of steps** (each key or mouse event, each focus change), each queued separately on the UI thread and waited for; actions run **one at a time** (parallel tool calls wait), so their steps never interleave; like the gaps between real input events. A step that opens a modal dialog does not block the next ones (the dialog's event loop runs them). An action that cannot go on (focus refused, a dialog interrupts typing, a disabled menu item) stops with a plain message and is reported as an error, with the snapshot | What one event causes (a focus change, a dialog) must happen before the next event, as with a real keyboard and mouse; the AI needs to know why an action did not happen |
| D31 | Tables, lists, trees and combo boxes are read with `read_items`, **as the cell renderers show them**, with the model value where it reads differently (dates as ISO); paging with `start`/`limit`, `find` over all columns, a subset of `columns`; sorted or filtered tables give the model row too. The snapshot stays short: at most 10 rows, 8 columns and 40 characters per cell (trees: 20 rows around the selection), with a pointer to `read_items`. Tree nodes are addressed by **paths of node texts** ("Root/Child" or "Root > Child", the root may be left out); ancestors are expanded on the way, and a child that is not there yet is waited for | Large tables must not flood the snapshot, but the AI must be able to check every row. Renderer text is what a user (and a test) sees; trees that load children on expansion only fill in when expanded, as for a user |
| D32 | Menus are opened **level by level** (MenuSelectionManager, as the mouse does), so listeners that fill or enable items when a menu opens run; a disabled item is reported, not clicked. `select_menu` takes paths ("File > Export > PDF"), `context=true` right-clicks a component and uses its context menu. A click on an item of a closed menu takes the same route | Legacy applications often enable menu items when the menu opens; clicking a closed item directly skipped that code |
| D33 | Dialogs: `handle_dialog` presses a button by its text or by **role** (ok, yes, no, cancel, open, save, mapped through the look and feel's texts so it works in any UI language; "close" is the window's close box), types the answer of an input dialog, or puts a path into a file chooser (the standard UI's file name field, then its Open/Save button). Actions take `dialog=<button>` to answer the modal dialog they open (an error if none opens within 3 s). The result repeats the dialog's title and message | Confirmations and messages are where tests check outcomes; a one-call "click Delete, answer Yes" keeps scripts short and readable |
| D34 | When the application exits during a call (Exit, Alt+F4), the MCP server reports that as the result, not as an error | Closing the application is a valid outcome of an action |
| D35 | **Multiple selection is done with real clicks**: `click` takes modifier keys; `select_option` with `values`, `indexes` ("0,2,5-8") or `value`+`to` clicks the first entry plainly (Ctrl with `add=true`), the others with Ctrl and a range's end with Shift; `deselect=true` Ctrl-clicks only the selected ones. Entries are located (and scrolled to) just before each click; all are checked first, so a missing one stops before anything is clicked. In tables the click goes to the first cell the user cannot edit. The result lists what is selected afterwards and says when the selection mode allows less. A single `select_option` keeps setting the selection directly (D31) | The look and feel's own mouse code decides what Ctrl/Shift do (anchor, toggles, single-selection modes), as for a user; a click on a check box cell would toggle it |
| D36 | **One `drag` tool with modes**: `mouse` (in-application events), `transfer` (Swing data transfer), `os` (`java.awt.Robot`); `auto` (default) picks `transfer` for drag-enabled sources and `mouse` otherwise. Start and end by item (value/row/col, `at=before/on/after`, `edge=` for borders), slider value or divider location (`toValue`), `dx`/`dy` or x/y | Same shape as `type_text mode=os`: one place to learn; the AI names what to drag, not pixels. Exception to D7: a slider thumb's height is read from the look and feel's protected `thumbRect` on Java 8 only (Java 9+ uses the slider's middle) |
| D37 | Mouse drags are **dispatched to the window (or heavyweight) under the start point**, as the operating system does, so AWT's lightweight dispatcher picks the component, sends enter/exit and keeps the drag with the pressed component until the release (mouse grab). Press, moves spread over `durationMs` and release are separate UI steps. The result reports what changed for known components (slider value, divider, column widths and order, internal frame bounds) and, for custom-painted ones, whether their picture changed (waiting up to 1.5 s for background rendering) | Dispatching to the component directly would skip glass panes, child components such as a split pane's divider and the grab; real applications (a GIS view) render asynchronously |
| D38 | In-application mouse events are **refused on a drag source** (dragEnabled with a TransferHandler, or an AWT drag gesture recognizer): they would start the operating system's drag and drop, which then drops wherever the real pointer is, possibly into another program | Safety: a test must never drop data into the user's other programs |
| D39 | **Drag and drop without the native system**: the source's TransferHandler creates the data (`createTransferable`), the drop target under the end point gets drag-enter, drag-over and drop events whose data methods are overridden to read that data directly, and the source finishes with `exportDone` (MOVE/COPY/NONE, as the drop answered). The drop location (`JList`/`JTable`/`JTree`/text) comes from Swing itself. `createTransferable`/`exportDone` are protected: called by reflection on the application's own handler classes (and on Java 8); for the JDK's own handlers on Java 9+ the public `exportToClipboard` with a private clipboard and COPY is used | Works in the background and on a locked screen; `canImport`/`importData` get a real drop TransferSupport, so application code runs unchanged. Only public JDK API plus the application's own methods (D7) |
| D40 | `mode=os` (keys and drags) first **proves the window is in front**: it moves the pointer over the target and requires that move to arrive in the application; otherwise nothing is pressed. Robot pointer moves are measured with `MouseInfo` and corrected (JDK 8 moves relatively; on a 125% display it landed up to 10% off). The pointer is put back afterwards | Seen in testing: Windows kept another program in front while Java still believed its window was focused, so presses went to a browser. Exact start and end points are needed for slider thumbs and dividers |

## Components

```
swing2mcp/
├── agent/      Java 8, no dependencies. Runs in the application's JVM.
│               localhost HTTP + token. snapshot / screenshot / action endpoints.
├── mcp/        Java 17+, MCP Java SDK, stdio. Embeds agent.jar as a resource.
│               JVM listing, launch, attach, MCP tools.
├── maven-plugin/ Java 8. The only part the user adds to their project: swing2mcp:run, swing2mcp:setup.
└── demo-app/   Java 8 Swing test application (login, menu, tabs, table, form, modal).
               Uses the plugin in its pom like a real project.
```

Flow:

```
Claude Code ──MCP(stdio)──▶ mcp (bridge) ──localhost HTTP──▶ agent (inside the product's JVM)
```

## MCP tools (draft)

| Tool | Description |
|---|---|
| `launch(command)` | Starts the application with the agent attached |
| `list_jvms` / `attach(pid)` | Connects to a running application later |
| `snapshot(window?)` | Text form of the component tree, with `ref` IDs (similar to Playwright aria-snapshot) |
| `screenshot(window? \| ref?)` | PNG via `component.paint()`; works even if the window is in the background |
| `click(ref)` / `type(ref, text)` / `select(ref, value)` | Actions; on the EDT, with synthetic events (does not take over the real mouse) |
| `wait_for(text \| ref)` | Waits for something to appear on the screen |

Example snapshot output:

```
- frame "Customer Manager" [ref=w1]
  - textfield "Name" value="Ali" [ref=c12]
  - combobox "City" selected="Ankara" [ref=c13]
  - table rows=24 cols=5 [ref=c14]
  - button "Save" enabled [ref=c15]
```

Naming order for fields without a label: the `setLabelFor` relation, then AccessibleName,
then tooltip, then the JLabel to its left/above it, and last the field's variable name in Java
(via reflection, e.g. `txtCustomerName`).

## Roadmap

| Milestone | Content |
|---|---|
| **M1** ✅ | demo-app + agent (with `-javaagent`) + bridge: snapshot, screenshot, click, type, end to end |
| **M2** ✅ | Maven plugin: `mvn swing2mcp:run` (starts the application with the agent), `mvn swing2mcp:setup` (installs the MCP server, writes the AI tools' settings) |
| **M3** ✅ | Attach: connecting later to a running, logged-in application (`connect_app` places the agent if needed) |
| M3b | Login macro: automatic login on restart |
| **M4** ✅ | Spring: Spring demo (demo-spring); the agent finds the context itself; `spring_beans`, `open_spring_bean` (opening a screen without navigating the menu) |
| **M5** ✅ | Hot reload: loading compiled classes into the running JVM (`hot_reload`); rebuilding screens whose setup code changed, keeping the screen state for other changes |
| **M6** ✅ | A fast, trustworthy edit → reload → check loop on a large legacy application: multi-module hot reload, waiting before actions, application errors in tool results, snapshot fixes |
| **M7** ✅ | Like a real user: real key and mouse events, reading tables and trees, dialogs, menu paths |
| **M7.1** ✅ | Multiple selection with Ctrl/Shift, mouse drags (maps, sliders, dividers, columns), drag and drop through the application's TransferHandlers, real-mouse drags (`mode=os`) |
| (open) | Opening a panel by class name in applications without Spring (`open_panel(class)`) |

## M1 results (2026-10-29, macOS)

- The demo application ran end to end over the MCP protocol on Java 8 (Zulu 8u512) and Java 21:
  launch, login, filling in a form, saving, table, tree, list, spinner, menu, tabs,
  modal dialog and screenshot.
- About 150–250 ms per action; 150 ms of that is a deliberate wait for the UI to settle.
- On Java 21, reflection into application classes (`field=` names) worked without `--add-opens` (D7 confirmed).
- Agent threads are daemons; they do not keep the application from exiting.
- `launch_app` adds the agent via `JAVA_TOOL_OPTIONS`. That way one approach is enough for `java -jar`, `.bat`/`.sh`
  scripts and IDE commands.

### Deliberately left out of M1

- `type_text` sets the value with `setText`; it does not generate keystrokes. Fields that validate with a KeyListener or
  InputVerifier/focusLost may not be triggered. Real key events
  will be added in a later step.
- Keyboard shortcuts / sending keys (`press_key`: Enter, Esc, Ctrl+S) are not there yet.
- Drawing ref labels on top of the screenshot is not there yet.

## M3 results (2026-10-31, macOS)

- Connected later, on Java 8 and Java 21, to an application started without the agent (plain `java -jar`);
  placing the agent took about 0.25 s. The application kept its state: the logged-in session and the
  unsaved text in the form were still there in a new AI session.
- On a Java 8 target, Java 17's attach client mistakes success for an error (`AgentLoadException: 0`);
  this case is recognized and counted as success.
- On a Java 21 target, an "agent loaded dynamically" warning shows up on the application's stderr; it does not
  affect operation. If a future JDK version turns dynamic loading off by default,
  `-XX:+EnableDynamicAgentLoading` or `mvn swing2mcp:run` will be needed.
- For attach, the MCP server must run on a JDK, not a JRE (`jdk.attach` module).
  On a JRE the list comes back empty and `connect_app` gives a clear error.

## M4 results (2026-10-31, macOS)

- demo-spring (Spring 5.3, XML + annotations) was used with the existing tools without changes.
- The context was found through the JVM shutdown hook, with `mvn swing2mcp:run` on Java 8 and by connecting later
  (attach) on Java 21. On Java 9+ the agent opens `java.lang` to itself via Instrumentation.
- `open_spring_bean`: a prototype dialog bean was opened without touching the menu; for the panel already on screen
  it said "already on screen"; for a service bean it returned a clear error. In an application without Spring
  `spring_beans` says "Spring not found".
- Not verified: the static holder and object graph routes (in the demo the shutdown hook finds it first);
  trying it with Spring 3.x/4.x and Spring 6.

## M5 results (2026-10-31, macOS)

- demo-app with `mvn swing2mcp:run` on Java 8, without ever closing the application: compile ~1.1 s,
  `hot_reload` ~0.17 s.
  - Only `saveCustomer` changed → the screen was not rebuilt, typed text and selection were kept, the new
    message appeared on the next click.
  - `initComponents` changed → `CustomerPanel` was rebuilt in place; the buttons work.
  - `paintComponent` changed → no rebuild, the chart has the new color.
  - New method and new lambda → rejected without being applied, with an explanation; after the change was
    reverted and compiled, it said "no changed classes".
- demo-spring, opened with plain `java` on Java 21 and connected to later: the panel built with `@PostConstruct init()`
  was rebuilt in place; a service change was applied without touching the screen.
- Application running with `java -jar`: a clear error without `classesDir`; with `classesDir` 26 classes
  were loaded; `ReportPanel`, which has no init method, was replaced with a new instance and its reference updated;
  for the main window built in the constructor, a "close and reopen it" message.
- Known limits: static initializers do not run again; in multi-module projects, classes in another module's
  jar are not loaded (only class folders; addressed in M6); new Spring beans are not added; when rebuilding
  in place, listeners that the init method registers elsewhere may be duplicated. The structural change route
  with JetBrains Runtime was not tried.

## Windows test results (2026-11-03, Windows 11, display scale 125%)

JDK 21 (Temurin), JDK 8u504, Maven 3.9.16; the MCP server was driven with `scripts/mcp-steps.py`.

- What works: `mvn install`, `setup` (paths `C:\...`, the `mcpJavaHome` message when Maven is on Java 8),
  `run` (Java 8), Turkish characters (snapshot and screenshot), attach to a Java 21 application
  (state kept), Spring (Java 8: `spring_beans`, `open_spring_bean`), the three hot reload scenarios
  (Java 8), `launch_app` with a Java path containing spaces (Java 21).
- Screenshots are sharp at 125%; they are at logical pixel size (Java 8: 882x573, Java 21: 886x583),
  so smaller than the window on the screen but not blurry.
- **Fixed:** attaching from the MCP server on Java 21 to a Java 8 application reports success on Windows as
  `AgentLoadException: Failed to load agent library: 0` (only `0` on macOS).
  `Attacher` counts both as success; the 10 s check that waits for the agent's registration still catches real
  errors.
- **Fixed (hot reload message):** When the running classes were compiled with JDK 21 and the change with JDK 8,
  the JVM rejected loading with "class modifiers". Cause: JDK 8 javac marks anonymous classes in a static
  context as `final`, JDK 9+ does not; even if the source does not change,
  the class looks "changed". The class flags are now compared in the structural check;
  the rejection message lists the affected classes and explains "compiled with a different JDK/compiler, compile with the same one"
  (user's choice: only a clear message; ignoring the differences was not implemented).
- `mcp-steps.py` read stdin as cp1254 on Windows (Turkish steps were garbled); fixed to read UTF-8 (including files
  with a BOM), and `--save-images` was added.
- `scripts/windows-manual-test.ps1` for the checks to do by hand (Ctrl+C, display scale, AI tool).

## M6 results (2026-11-05, Windows 11)

Tried on a large legacy Swing application (Java 8, Spring Boot 2.1, a custom look-and-feel and window
framework, two Maven modules: the shared code and a launcher module with the main class), started with
`mvn swing2mcp:run` from the launcher module without changing its poms. The test change was a message
dialog in a button handler of the shared module (compile: `mvn -o compile` of that module, ~9 s).

- **Multi-module hot reload.** Before: the shared module came from its jar in `~/.m2`, `classesDir` had no
  baseline, so all 261 loaded classes of the module were reloaded (19 s) and 4 unchanged screens were
  rebuilt. Now:
  - same start, `hot_reload classesDir=<module>/target/classes`: **1 class** reloaded in 0.6 s, no screen
    rebuilt (the original bytes come from the jar);
  - started with `-Dswing2mcp.classesDirs=<module>/target/classes`: `hot_reload` without arguments, again
    1 class; reverting the change, 1 class.
- **Waiting before actions.** The login field appears while a modal "loading" dialog is still open;
  `type_text` now reports `Waited 226 ms (blocked by modal dialog "")` and types. Buttons inside the
  framework's internal frames (visible glass pane with a mouse listener) are not taken as blocked.
- **Application errors.** A test handler threw in the click handler, in an `invokeLater` and in a new thread:
  the click result listed all three with stack traces; the next snapshot listed none. The application had no
  handler of its own, and the exceptions were still printed to the console as without the agent.
- **Snapshot.** Internal frames are listed once (the framework's window facade delegated `equals`/`hashCode`
  to the internal frame, so it even got the same ref). Labels of the framework's label class that name a
  field no longer show up as "custom-painted".
- Regression on demo-app (Java 8): login, disabled button and modal dialog time-outs with clear messages,
  hot reload of `saveCustomer`.

## M7 results (2026-11-05, Windows 11)

The AI now operates the application through the same code paths as a user. Tools added: `press_key`,
`read_items`, `expand_node`, `select_menu`, `handle_dialog`; `type_text` types real keys; `click` uses real
mouse events; actions take `dialog=<button>`.

- **demo-app (Java 8)**, which got an email field with an input verifier, a digits-only phone field
  (key listener), a long sortable invoice table with renderers, a tree node that loads its children
  on expansion, an input dialog, a file chooser, a menu that enables an item when it opens and a context
  menu:
  - login with `type_text`, `press_key Tab` and `Enter`; Ctrl+N and Ctrl+G shortcuts; Alt+F, Down, Enter;
    Escape closes message boxes;
  - "05a32-12" typed into the phone field gives "053212" and the result says keys were rejected;
    `mode=set` still bypasses the key listener;
  - an invalid email keeps the focus: typing elsewhere is refused with the reason, and a click on Save
    reports it (the action still runs, as in Swing); a spinner commits its typed value when the focus
    leaves it;
  - the same with the window **inactive** (not the foreground window): keys, Tab, verifier, spinner
    commit and dialogs work; the user's keyboard is not used;
  - `read_items`: paging, `find` (5 of 120 rows), column subsets, renderer text with model values,
    model rows after sorting by a header click;
  - trees: the lazily loaded node is expanded and its grandchild selected by path after waiting for the
    load (324 ms); collapse; a missing node fails with the list of existing ones;
  - dialogs: Delete answered with no/yes, the validation message read, the input dialog answered with
    text, the file chooser answered with a file, a directory (it navigates and stays open, and says so),
    cancel and close;
  - menus: the item enabled on menu open, a disabled item reported, nested paths, context menu with
    `dialog=yes`; File > Exit with `dialog=yes` reports that the application exited.
- **demo-spring (Java 8)**: login with keys, a custom dialog opened by menu path, spinner typing, Spring
  beans, and a hot reload of a service message checked with `dialog=ok` (and reverted).
- **The large legacy application** (custom look and feel and window framework, Turkish UI): the
  language and login screens advanced with Enter; a name field's 48-character limit was enforced and
  reported; the framework's message boxes were answered with ok/yes/no, which matched "Tamam", "Evet",
  "Hayır"; multi-line warnings were read; Escape closed a custom dialog; the scenario and model trees
  were read, expanded and selected by path; nested menus and a check box menu item worked; a check box
  cell of a table was toggled by a real click.
- The machine's screen was locked during part of the tests: in-JVM keys and mouse events and screenshots
  kept working. `mode=os` correctly reported that the keys did not arrive; its normal path still needs a
  check with someone at the screen.
- Not covered: drag and drop, multiple selection with Ctrl/Shift clicks, the inactive-window path on the
  legacy application, typing through native input methods (IME).

## M7.1 results (2026-11-15, Windows 11, display scale 125%)

Tools: `drag` (new); `click` takes `modifiers` and `button=middle`; `select_option` takes `values`, `indexes`,
`to`, `add`, `deselect`; `read_items` and the snapshot report the selection mode and multiple selections as
ranges ("selected=[2-4,30]", all selected tree paths); the snapshot lists split panes with their divider and
heavyweight canvases.

- **demo-app (Java 8)** got a "Planner" tab: two multi-select lists with a TransferHandler (move/copy,
  reorder), a table and a plain label as drop targets, a split pane, a zoom slider and a custom-painted map
  that pans with the mouse.
  - Multiple selection in lists, the invoice table and the category tree: values, index ranges, add,
    deselect, Ctrl/Shift clicks; a single-selection table is reported.
  - Mouse drags: the map panned exactly by the drag; slider to a value; divider by an offset and to a
    location; a column resized by its border (169 → 229 px) and moved. A drag from a table that is not a
    drag source is a plain mouse drag and the result says so; a mouse drag on a drag-enabled list is refused.
  - Drag and drop: two selected items moved to another list before a given item, an item reordered in its
    list, Ctrl+drag copied to the table (insert row), a move onto the label deleted the item; refusals
    (no drop target, link not allowed) are errors with the reason.
  - All of this also with the screen locked and with another program in front.
  - `mode=os` with someone at the screen: slider (exactly 300), divider (360), map (exactly 150,80) and a
    native list-to-list drag and drop dropped before the given item; `type_text`/`press_key mode=os`
    typed. With an always-on-top window over the application, `drag` and `press_key mode=os` refused and
    that window received no press and no key.
- **demo-spring (Java 8)**: login with keys, multi-select in the customer table, menu path, spinner, Spring
  beans.
- **The large legacy application** (Java 8, its GIS map is a lightweight Swing component that renders in
  the background): the map panned by a mouse drag with the screen locked (a placed object moved exactly by
  the drag; the cursor-position fields in the status bar confirmed it); dragging a model from its model
  tree onto the map created an object there, through the application's own mouse code (the tree is not a
  Swing drag source); multiple selection in its scenario tree and in a table; its model tree is
  single-selection and was reported as such.
- Found and fixed during the tests: Robot on JDK 8 moves the pointer relatively (up to 10% off at 125%),
  now measured and corrected; Java may report a window as focused while Windows keeps another program in
  front, so `mode=os` now proves the window is in front first (D40); a picture comparison that rounded
  small changes to "no change".
- The last three fixes were checked on the legacy application too: the map's picture change is reported
  (<1% for a pan of a mostly empty map), the note after a drag from its model tree (which created another
  object), and a multi-select in a table whose first column is a check box left the check boxes alone.
- Not covered: AWT drag gesture sources in `transfer` mode (they need `mode=os`); trees as drop targets.

## Risks / to be verified

- **Windows:** If the user folder path contains a space (`C:\Users\Ali Veli`), `launch_app`
  quotes the agent path; verified on macOS that Java 8 accepts the quoted path,
  not yet tried on Windows with a user folder containing spaces (the test machine has none).
  Test steps: [WINDOWS-TEST.md](WINDOWS-TEST.md).

- Attaching from the bridge on JDK 17 to a Java 8 JVM (expected: works; to be verified in M2).
- Modal dialogs: if an action is triggered with `invokeAndWait`, the EDT blocks. Actions should be
  triggered with `invokeLater` and then waited for.
- Dynamic attach warning on Java 21 (JEP 451); if needed, `-XX:+EnableDynamicAgentLoading`.
- Custom-painted components (tables that paint themselves with paintComponent, etc.) may be missing
  from the text snapshot; a screenshot fallback is used for them.
- Security: the agent listens only on localhost, uses a random token and is off
  by default.

## Open questions / concerns

### C1: Concern about direction (2026-10-30, before M2)

The first idea was a **developer tool**: it would be added to the project through Maven, and then
any AI tool such as Claude, Cursor or OpenCode could connect to it easily. Concerns:

1. **Tool lock-in:** The solution should not be specific to Claude Code; other tools are used at work too.
   The connection step should be easy for every tool.
2. **Capability:** Does going for an agent attached to a running application limit the capabilities that
   integrating more deeply into the JVM as a plugin/library would provide?
3. **Installation style:** While drifting towards a launch/attach-focused tool for "ease of use", are we moving away
   from being a developer tool that is included in the project with Maven?

Status (2026-10-30): Discussed → D11 and D12. The agent does not limit capability (it can do
everything a library can and more); what was missing was the entry point that comes with Maven.
Attach was moved to after the Maven plugin. The interim decision for attach stands: `connect_app`
places the agent itself if needed.

### C2: How to reach Spring → decided (D18)

In Spring applications, features such as "open the panel directly" or "skip the login by calling a service"
need access to the `ApplicationContext`. Options:

- **Through the agent (zero touch):** The moment Spring creates the context at application startup is
  intercepted. Reliable for applications started with the Maven plugin; harder with a later attach,
  because the context has long been created by then.
- **Through a Maven dependency (optional):** A small `swing2mcp-spring` module is added to the
  application and hands over the context itself. The most reliable way, but it touches the product's pom
  (it can go into a dev profile).

Leaning: the agent first; the dependency becomes an optional extra. The decision will be made in the Spring milestone,
by trying it with a Spring variant of the demo.

User's view (2026-10-30): Instead of invisible things that could cause security problems,
control given explicitly through Maven is preferred; so the dependency route seems more reasonable.
But it must not introduce a new delivery part or management layer (D13). Possible solution:
instead of a separate module, also use the existing agent artifact as an optional dependency.

Findings from the trial (2026-10-31, demo-spring, with attach, without touching the application):
- The whole `ApplicationContext` was found through the JVM shutdown hook (`registerShutdownHook`).
  Common in desktop Spring applications; on Java 9+ the agent has to open `java.base` to itself.
- The bean factory was reached by following the fields of screen objects (depends on the application, not guaranteed).
- Scanning for a static "SpringContext holder" class finds nothing in this demo (a pattern often seen in legacy projects).
- The dependency route also needs a line added to the Spring configuration (XML bean or `@Import`) next to the pom:
  plain Spring has no auto-discovery. So it touches the product code.

### C3: Connecting over HTTP (postponed)

The AI tool connecting to an address (`http://localhost:.../mcp`) instead of running a command.
This is the shortest path on the settings side, but it needs a process that is always running and a fixed port.
