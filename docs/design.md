# Design notes

Why swing2mcp is built the way it is. For what it does and how to use it, see the [README](../README.md).

## Problem

- The feedback loop of a legacy Swing application is long: a restart with login often takes more than a
  minute, and an AI coding tool that changes the code has to go through it for every attempt.
- There is nothing like Playwright for Swing: no way to connect to an open application, read the screen
  as text, press a button and check the result.

## Architecture

```
AI tool ──MCP (stdio)──▶ MCP server (Java 8+) ──localhost HTTP + token──▶ agent (inside the application's JVM, Java 8+)
```

| Module | Java | Role |
|---|---|---|
| `agent` | 8, no dependencies | Runs inside the application: snapshot, screenshot, actions, Spring lookup, hot reload |
| `test-library` | 8, no dependencies | Tests in Java that use an application through the agent, without MCP. Carries the agent jar and the code that starts and talks to applications |
| `mcp` | 8, no dependencies | MCP server. Its jar includes the connection code and the agent jar; starts applications or attaches to them |
| `maven-plugin` | 8 | The only part a user adds to a project: `swing2mcp:setup` and `swing2mcp:run` |
| `demo-app`, `demo-spring` | 8 | Legacy-style test applications, without and with Spring. They know nothing about swing2mcp |

"Agent" here is a *Java agent*: a jar loaded into a JVM with `-javaagent` or at run time through the
Attach API. It has nothing to do with an AI agent.

## Principles

- **The application is not touched.** No code, build or dependency of the application changes. Remove
  the agent and nothing is left behind.
- **Nothing happens invisibly.** The agent is only placed into an application on an explicit request:
  `swing2mcp:run`, `launch_app`, or `connect_app` with a pid. Automatic connection applies only to
  applications that already have the agent.
- **Any AI tool.** The connection is plain MCP; nothing is specific to one client.
- **Few parts.** A user sees one thing, the Maven plugin. The agent and the MCP server come with it.
- **Like a real user.** Actions go through the same code paths as a person's keyboard and mouse, so the
  application's listeners, validation and focus handling run.

## Decisions

### Two processes

- The agent stays small: Java 8 bytecode, no dependencies (the JDK's own `HttpServer`, a small
  hand-written JSON writer). It cannot conflict with the application's libraries and runs on Java 8 to 21.
- The agent uses only public Swing/AWT API and reflection into the *application's* classes, never into JDK
  internals, so it needs no `--add-opens` on newer JDKs. Two narrow exceptions: Spring lookup opens
  `java.lang` to the agent through `Instrumentation` on Java 9+, and on Java 8 a slider thumb's size is
  read from the look and feel.
- The MCP protocol lives in a separate process. It is in Java because the Attach API is only reachable
  from Java, and the audience already has a JDK.
- The MCP server is written without the MCP SDK, in Java 8 and with no dependencies: the SDK needs
  Java 17, and many machines of the audience only have Java 8. The server needs a small part of the
  protocol (stdio, tools), and the tool definitions are a resource file (`tools.json`).
- The MCP server jar carries the agent jar and injects it, so there is one thing to install.
- Looking at the screen from outside (screenshot, OCR, real mouse) was rejected: general computer-use
  tools already do that, and it adds nothing Java-specific.

### Delivery

- Build integration starts with Maven; the build-specific part is thin, and the engine (agent and MCP
  server) does not depend on a build system. Gradle or Ant support can be added the same way.
- `swing2mcp:setup` installs the MCP server under `~/.swing2mcp/bin` and adds it to the user settings
  of the AI tools the developer picks (Claude Code, Cursor, Codex, OpenCode), keeping existing settings.
  It lists its changes and asks first, and writes nothing into the project; `swing2mcp:uninstall`
  removes everything again.
- `swing2mcp:run` compiles, starts the application in its own process with the project's classpath and the
  agent, and waits; when Maven stops, the application stops. Long classpaths go into a manifest jar
  because of the Windows command-line limit.
- The plugin is compiled for Java 8, because legacy builds often run Maven itself on Java 8.
- `launch_app` adds the agent through `JAVA_TOOL_OPTIONS`, so one approach covers `java -jar`, start
  scripts and IDE commands.

### Seeing the application

- The snapshot is a text tree of the windows with `ref` IDs, similar to Playwright's aria snapshot.
  Refs are assigned by object identity, not `equals`.
- A field without a label is named, in order, by its `setLabelFor` label, its accessible name, its
  tooltip, the label to its left or above it, and last the name of the Java field that holds it
  (`field=txtCustomerName`). The field name also ties the screen to the source code.
- The snapshot stays short (at most 10 rows, 8 columns, 40 characters per cell). `read_items` reads
  tables, lists, trees and combo boxes page by page, as the cell renderers show them, with the model
  value where it reads differently.
- Tree nodes are addressed by paths of node texts ("Root > Child"). Ancestors are expanded on the way and
  lazily loaded children are waited for.
- Screenshots are painted by the component itself, so they work for a window in the background.

### Acting like a user

- **Keys** are real key events dispatched inside the JVM through the `KeyboardFocusManager` to the
  focused component. The window is never brought to the front: the developer is usually typing in
  another program, and keys sent through the operating system could end up there.
- **Typing** focuses the field first (the previous field's input verifier runs and may refuse), then
  sends each character as pressed/typed/released. Key listeners, document filters and length limits run;
  the result says when the application changed or rejected keys. `mode=set` calls `setText` instead.
- **Clicks** are a real mouse press and release, also for buttons. The press moves the focus first, so
  focus-lost handlers (such as a formatted field committing its value) run before the button's action.
- **An action is a list of steps**, each queued on the UI thread and waited for, and actions run one at a
  time. What one event causes (a focus change, a dialog) happens before the next event, as with a real
  keyboard and mouse.
- **Actions wait first** (default 10 s) until the component is showing, enabled, editable where needed
  and not blocked by a modal dialog or a busy glass pane. This removes the race between "the field
  appeared" and "the field can be used" without extra calls.
- **Menus** open level by level, as with the mouse, so listeners that fill or enable items when a menu
  opens run. A disabled item is reported, not clicked.
- **Dialogs** are answered by button text or by role (ok, yes, no, cancel...), mapped through the look and
  feel's texts so it works in any UI language. An action can answer the dialog it opens (`dialog=yes`).
- **Multiple selection** is done with real Ctrl and Shift clicks, so the look and feel decides what they
  mean, as for a user.
- **Uncaught exceptions** of the application are reported in the next tool result, each once, with a short
  stack trace. The agent wraps the default handler and passes every exception on, so the application's
  own error handling stays as it was. Log output is not captured.
- When the application exits during a call, that is reported as the result, not as an error.

### Dragging

- One `drag` tool with modes: `mouse` (in-application events), `transfer` (Swing data transfer) and `os`
  (`java.awt.Robot`). `auto` picks `transfer` for drag-enabled sources and `mouse` otherwise.
- Mouse drags are dispatched to the window under the start point, as the operating system does, so glass
  panes, child components and the mouse grab behave as usual.
- Drag and drop runs without the operating system: the source's `TransferHandler` creates the data, the
  drop target gets real drop events at a real drop location, and the source finishes with `exportDone`.
  It works in the background and on a locked screen.
- In-application mouse events are refused on a drag source. They would start a native drag that drops
  wherever the real pointer is, possibly into another program.
- `mode=os` (keys and drags) first proves the window is in front: it moves the pointer over the target and
  requires that move to arrive in the application, otherwise nothing is pressed. Java can report a
  window as focused while the operating system keeps another program in front.

### Tests without an AI tool

- The agent's HTTP interface is the core; MCP is one front end to it and the test library is another. A
  test talks to the agent directly, so a build server needs neither an MCP server nor a model.
- A ref lives for one run of the application, so tests name components by locator: role and name as the
  snapshot shows them, the Java field, or what the component is inside. The agent resolves locators with
  the snapshot's own code, so a test and an AI tool can never disagree about what a component is called.
- A locator must match exactly one component. Nothing matching is waited for; several matching is an
  error at once that lists them.
- The test API has one method per kind of component and returns a type that only offers what that kind
  can do, so a misspelled kind or a meaningless action does not compile.
- The test library's jar contains no MCP code and cannot load the agent into a running process; it only
  starts applications with the agent or connects to ones that have it.

### Spring

- The agent finds the Spring context itself: static fields in application classes (a "context holder"),
  the JVM shutdown hook that `registerShutdownHook` leaves, then the object graph behind the visible
  windows. Spring is never referenced at compile time, so any Spring version works, and so does no Spring.
- An optional dependency that hands over the context was considered and left out: it needs a line in the
  application's Spring configuration, which touches the product.

### Hot reload

- `hot_reload` does not compile. The AI tool or the IDE compiles; the agent applies the compiled classes.
  This keeps it independent of the build system.
- Changes are detected per method (a digest of the bytecode that ignores the constant pool and line
  numbers). Only classes whose code really changed are reloaded, even when a build rewrote every file.
- A screen is rebuilt only if its setup code changed (the constructor, an init method and what they call);
  otherwise its state is kept, so typed data survives a "fix and retry" loop. Rebuilding happens in
  place by calling the init method again (`initComponents`, `jbInit`, `@PostConstruct`...), or with a new
  instance from the no-argument constructor, or else the tool says to reopen the screen.
- Structural changes (a new method, field or lambda, a changed superclass) cannot be applied on a
  standard JVM. They are rejected with an explanation and nothing is applied: all or nothing. On
  JetBrains Runtime or DCEVM they are applied.
- The baseline for a loaded class is the class as it was loaded, read from its code source (the jar, or a
  class file older than the JVM). In multi-module projects `run` takes an explicit
  `-Dswing2mcp.classesDirs` list; sibling modules are not guessed, because a wrong guess would be
  invisible.

### Security

- The agent listens only on `127.0.0.1`, on a random port, and requires a random token on every request.
- The port and token are written to `~/.swing2mcp/jvms/<pid>.properties`, readable only by its owner, and
  removed when the application exits.
- Attaching at run time shows a warning on Java 21+ (JEP 451). If a future JDK turns dynamic loading off,
  `-XX:+EnableDynamicAgentLoading` or `swing2mcp:run` is needed.

## Known limits

- Hot reload: static initializers do not run again, new Spring beans are not added, and an init method
  that registers listeners elsewhere may register them twice when a screen is rebuilt in place. Classes
  compiled with a different JDK than the running ones can look structurally changed; the message says so.
- Attach needs the MCP server to run on a JDK, not a JRE.
- AWT drag gesture sources without a `TransferHandler` only work with `mode=os`; trees as drop targets
  are untested.
- Custom-painted components have no text in the snapshot; use a screenshot for them.
- Not tried: Spring 3, 4 and 6, structural hot reload on JetBrains Runtime, native input methods (IME), a
  Windows user folder with spaces.

## Ideas not done yet

- Opening a screen by class name in applications without Spring.
- Drawing the ref labels on screenshots.
- Connecting over HTTP instead of stdio. It is the shortest setup, but needs a process that is always
  running and a fixed port.
- Recording the steps of a session and replaying them without an AI tool, as a repeatable test.
