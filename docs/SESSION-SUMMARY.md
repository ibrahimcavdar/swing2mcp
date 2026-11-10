# Session summary (M1–M7, 2026-10-28 → 2026-11-05)

This file was written to continue the project on another computer with another AI agent. The first
session took place on macOS; the next step is testing on Windows (see [WINDOWS-TEST.md](../WINDOWS-TEST.md)).

## What is the project?

A developer tool for legacy Java Swing applications. AI coding tools (Claude Code,
Cursor, Codex, OpenCode) can **see** a running Swing application (text snapshot +
screenshot) and **use** it (click, type, select). The application's code does not change.

The two pain points it started from:
1. Opening the application and logging in takes 1+ minute; every attempt means waiting.
2. When the AI changes the code, the application has to be closed and reopened.

Goal: share it with other teams at work and make it open source. The target environment is mostly
**Windows**, the applications are often **Java 8**, with or without Spring.

## Principles

- **Every milestone is committed and tagged** (`m1`, `m2`...). Risky work is done on a separate branch
  and fast-forwarded into `main` when done, so going back to the previous tag is always possible.
- Client-independent (not specific to Claude Code). Maven-focused but extensible.
- No invisible or security-wise problematic automatic behavior; control is given explicitly.
- Few parts to deliver.

## Architecture (short)

```
AI tool ──MCP(stdio)──▶ swing2mcp MCP server (Java 17) ──localhost HTTP + token──▶ agent (in the application's JVM, Java 8)
```

| Module | Java | Role |
|---|---|---|
| `agent` | 8, no dependencies | Inside the application: snapshot, screenshot, actions, Spring, hot reload |
| `mcp` | 17 (MCP Java SDK 2.1.0) | MCP server; embeds the agent jar, does launch/attach |
| `maven-plugin` | 8 | The only part the user adds: `swing2mcp:setup`, `swing2mcp:run` |
| `demo-app` | 8 | Legacy-style test application without Spring (admin/admin) |
| `demo-spring` | 8, Spring 5.3 | Test application with Spring (admin/admin) |

All decisions and their rationale: [DESIGN.md](../DESIGN.md) (D1–D34, open questions C1–C3).

## Milestone history

| Tag | What was done | How it was verified |
|---|---|---|
| `m1` | Agent + MCP server + demo-app. snapshot, screenshot, click, type_text, select_option, wait_for, launch_app | Java 8 and 21, end to end over MCP stdio |
| `m2` | Maven plugin: `setup` (installs the MCP server, writes the settings of 4 tools), `run` (compiles the project, starts it with the agent). Codex support | Maven on Java 8; with a client connecting from `.mcp.json` |
| `m3` | `connect_app` places the agent into an application without one, without a restart (attach); `list_apps` shows all Java processes | Java 8 and 21; the application state was kept |
| `m4` | demo-spring; the agent finds the Spring context itself (static field, shutdown hook, object graph); `spring_beans`, `open_spring_bean` | run (Java 8) and attach (Java 21); polite error in an application without Spring |
| `m5` | `hot_reload`: finds changed classes at method level and applies them; rebuilds only the screen whose setup code changed; rejects structural changes with an explanation | run (Java 8), attach Spring (Java 21), `java -jar` + `classesDir` (Java 21); the M1–M4 flows were run again |
| `m5.1` | Windows test: fix for attaching to Java 8, a clear hot reload message when compiling with a different JDK, a manual test script | Windows 11: steps 2–7 with `mcp-steps.py`; Ctrl+C, screenshot and AI tool by the user |
| `m7` | Like a real user: real key events (`type_text`, `press_key`, also in an inactive window), real mouse clicks, `read_items` for tables/lists/trees, tree paths and `expand_node`, `select_menu`, `handle_dialog` and `dialog=` on actions | Windows 11, Java 8: demo-app (also with the window inactive), demo-spring (incl. hot reload), the large legacy application (login with Enter, its trees, menus and Turkish message boxes) |
| `m6` | Fast loop on a large legacy application: multi-module hot reload (original bytes from the jar, `-Dswing2mcp.classesDirs`), actions wait until the component is usable, uncaught application exceptions in tool results, snapshot fixes (window facades, label subclasses, refs by identity) | Windows 11, Java 8, a large legacy Swing application (two modules, Spring Boot 2.1) with `mcp-steps.py`; regression on demo-app |

## Status and next step

- `m7` (2026-11-05) was done on the `feature/m7` branch; details: DESIGN.md, "M7 results" (D27–D34).
- **Next step:** the open-source release (README, demo GIF, security notes), then m8 (step traces,
  record and replay without AI).
- `m6` (2026-11-05): details and numbers in DESIGN.md, "M6 results".
- All earlier work is on `main`; `m5.1` was the latest milestone before m6.
- **Windows (2026-11-03):** WINDOWS-TEST steps 2–7 were tried by an AI agent with `mcp-steps.py`
  on Windows 11 and passed. Two bugs were fixed (Windows' success message when attaching to Java 8;
  the confusing hot reload rejection after compiling with a different JDK). Details: DESIGN.md, "Windows test results".
- The user tried the things that need a person at the screen with `scripts\windows-manual-test.ps1` (Ctrl+C,
  screenshot, a real AI tool); the first AI attempts worked, the MCP connection had no problems. Tag: `m5.1`.
- Windows-specific precautions taken in advance: a manifest jar for long classpaths (`run`); a quoted agent path
  in `JAVA_TOOL_OPTIONS` for user folders with spaces (`launch_app`); the MCP SDK
  uses UTF-8 on stdio (no risk for Turkish characters); literal strings in TOML (backslashes).

## Open questions / possible next milestones

- User folder with spaces on Windows (not tried).
- `type_text mode=os` / `press_key mode=os` (java.awt.Robot) have only been seen failing cleanly on a
  locked screen; they need a check with someone at the screen.
- Drag and drop, multiple selection with Ctrl/Shift clicks.
- Opening a panel by class name in applications without Spring (`open_panel`).
- Drawing ref labels on the screenshot.
- Connecting over HTTP (C3).
- Not tried: Spring 3/4/6, the static SpringContext holder route, structural hot reload with JetBrains Runtime.

## Technical notes (pitfalls)

- Java 17's attach client reports success on a Java 8 target as `AgentLoadException: 0`
  (`Failed to load agent library: 0` on Windows); `Attacher` counts both as success.
- JDK 8 and JDK 9+ javac write the flags of anonymous classes in a static context differently; classes compiled
  with a different JDK than the one that compiled the running application are rejected by hot reload (the message says so).
- Windows PowerShell 5.1: if a `.ps1` file has no BOM, Turkish text gets garbled; `Start-Process -Wait`
  also waits for child processes (it hangs because of the application opened with `launch_app`).
- On the Windows test machine, Maven, JDK 8 and Python (embeddable) are under `%USERPROFILE%\tools`, not on
  PATH; the `python` command is a Microsoft Store shortcut. JDK 21 is installed system-wide (`JAVA_HOME`).
- Because of the embedded Java 17 MCP jar, the Maven plugin tooling marked the plugin as "requires Java 17".
  Fixed with `requiredJavaVersion=8` in `maven-plugin/pom.xml`.
- demo-spring needs the `javax.annotation-api` dependency for Java 11+ (`@PostConstruct`).
- In the macOS tests, background processes ignore SIGINT. The Ctrl+C behavior was tested with
  SIGTERM.
- Code that runs on the EDT must never loop on a condition that may not change: during m6 a glass pane
  check walked from a window's root pane back to the same root pane and froze the application.
- The running application keeps the agent jar in `~/.m2` open; on Windows `mvn install` may fail to replace it
  while the application runs. Stop the application before rebuilding the agent.
- Testing the inactive-window path: a window minimized with `ShowWindow(SW_MINIMIZE)` from another
  process stays the active window; let the application open and close a dialog afterwards, then the
  frame is inactive. Starting Notepad does not take the foreground from a background shell.
- In-JVM keys work on a locked screen; `java.awt.Robot` keys silently go nowhere there (`mode=os`
  detects that with an AWT event listener and reports it).
- The Bash tool's heredocs turn `\\` into `\` (also inside JSON test steps): use forward slashes in
  paths, and write edit scripts with a file instead of a heredoc.
- The hot reload baseline is recorded in the background when the agent starts. Class files written after
  the JVM started are counted as "unknown" and applied on the first reload.

## Development and testing

- Build: `mvn install` at the repository root with JDK 17+.
- Manual MCP testing: [scripts/mcp-steps.py](../scripts/mcp-steps.py). It starts the MCP server and
  runs the tool calls given as JSON lines on stdin in order (description at the top of
  the file). All end-to-end tests in the first session were done this way.
- If the demo sources are changed during tests, restore them at the end with `git checkout -- demo-app demo-spring`
  and rebuild.
