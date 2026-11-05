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
| M7 | Like a real user: real key events, reading tables and trees, dialogs |
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
