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

## Components

```
swing2mcp/
├── agent/      Java 8, no dependencies. Runs in the application's JVM.
│               localhost HTTP + token. snapshot / screenshot / action endpoints.
├── mcp/        Java 17+, MCP Java SDK, stdio. Embeds agent.jar as a resource.
│               JVM listing, launch, attach, MCP tools.
└── demo-app/   Java 8 Swing test application (login, menu, tabs, table, form, modal).
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
| M2 | Attach: connecting later to a running, logged-in application |
| M3 | Launch + login macro: automatic login on restart |
| M4 | Hot reload: reloading a compiled class in the running JVM (HotSwap → JetBrains Runtime/HotswapAgent) |
| M5 | `open_panel(class)`: opening a panel directly; taking beans from the ApplicationContext if Spring is present |
| M6 | Windows-specific topics (DPI, Robot fallback), Java 21 demo variant |

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

## Risks / to be verified

- **Windows:** If the user folder path contains a space (`C:\Users\Ali Veli`),
  the `-javaagent` path inside `JAVA_TOOL_OPTIONS` may be split on Java 8. Must be tested on Windows.

- Attaching from the bridge on JDK 17 to a Java 8 JVM (expected: works; to be verified in M2).
- Modal dialogs: if an action is triggered with `invokeAndWait`, the EDT blocks. Actions should be
  triggered with `invokeLater` and then waited for.
- Dynamic attach warning on Java 21 (JEP 451); if needed, `-XX:+EnableDynamicAgentLoading`.
- Custom-painted components (tables that paint themselves with paintComponent, etc.) may be missing
  from the text snapshot; a screenshot fallback is used for them.
- Security: the agent listens only on localhost, uses a random token and is off
  by default.
