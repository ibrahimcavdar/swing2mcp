# Changelog

Notable changes to swing2mcp. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). While the version is 0.x, anything may change
between releases.

## Unreleased

### Added

- Tool annotations: the tools that only read (`snapshot`, `screenshot`, `read_items`, `list_apps`,
  `spring_beans`, `wait_for`) are marked read-only and `stop_app` is marked destructive, for clients that
  use these hints to decide when to ask.
- Locators: wherever a tool takes a `ref`, it also takes a description of the component as the snapshot
  writes it, such as `button "Save"`, `field=txtName` or `dialog "Confirm" > button "OK"`. Unlike a ref, a
  locator stays valid from one run of the application to the next.
- Experimental test library, `swing2mcp-test`: tests written in Java use the application through the
  agent, without MCP or an AI tool, for example
  `app.textField("Full Name").fill("Ali"); app.button("Save").click();`. Its jar contains no MCP code.
  Names may change.
- `swing2mcp:setup` can also give the chosen AI tools a skill, `swing-ui-test`, that tells them how to
  write such a test after walking through a scenario; it asks, or takes `-Dswing2mcp.skill=true`.
  `swing2mcp:uninstall` removes it.

### Changed

- `stop_app` only stops applications that have the swing2mcp agent. Any other process id is refused, so a
  wrong number cannot end an unrelated program. Its description now says that unsaved work is lost.

## 0.1.0 - 2026-11-21

First public release, on Maven Central (`io.github.ibrahimcavdar`).

### Added

- **Seeing the application:** `snapshot` (text tree of the windows with refs and Java field names),
  `screenshot`, `read_items` (tables, lists, trees and combo boxes, page by page), `wait_for`.
- **Using it like a real user:** `click`, `type_text` and `press_key` with real mouse and key events that
  also work while the window is in the background; `select_option` including multiple selection with Ctrl
  and Shift; `select_menu`, `expand_node`, `handle_dialog`, and `dialog=` on actions to answer the dialog
  they open; `drag` for mouse drags and for drag and drop between components.
- Actions wait until the component can take input, and report exceptions the application did not handle.
- **Connecting:** `launch_app` and `stop_app`; `list_apps` and `connect_app` to attach to an application
  that is already running, without a restart.
- **Hot reload:** `hot_reload` applies recompiled classes to the running application, reloads only the
  classes whose code changed, rebuilds only the screens whose setup code changed, and rejects structural
  changes with an explanation. Works across the modules of a multi-module project.
- **Spring:** the agent finds the Spring context without touching the application; `spring_beans` and
  `open_spring_bean`.
- **Maven plugin:** `swing2mcp:setup` asks which AI tools to connect (Claude Code, Cursor, Codex,
  OpenCode), lists its changes before making them, and writes to the user's settings, not into the
  project; `swing2mcp:uninstall` removes everything again; `swing2mcp:run` starts the project's
  application with the agent.
- The agent, the MCP server and the Maven plugin run on Java 8 and newer; the MCP server has no
  dependencies.
- Two demo applications, without and with Spring.
- End-to-end tests that use the demo application through the MCP server; they run in `mvn install` and
  in CI.
- `SECURITY.md` with the security model and how to report a vulnerability.
- License: Apache License 2.0.
- Tested on Windows 11 and Linux, with Java 8, 21 and 25.
