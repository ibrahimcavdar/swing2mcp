# Changelog

Notable changes to swing2mcp. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). While the version is 0.x, anything may change
between releases.

## Unreleased

### Added

- License: Apache License 2.0.
- `SECURITY.md` with the security model and how to report a vulnerability.
- End-to-end tests that use the demo application through the MCP server; they run in `mvn install` and
  in CI.
- `mvn swing2mcp:uninstall` removes everything `setup` added.

### Fixed

- `wait_for` reports a timeout as an error; it used to return a normal result that only said "Timed out".
- A spinner shows text that was typed but not taken over yet (`typed="5" (not committed)`), and `type_text`
  says how to commit it. Before, the result showed the old value without a hint.

### Changed

- The MCP server runs on Java 8 and newer (it needed Java 17) and has no dependencies; tested on Java 8,
  21 and 25.
- `mvn swing2mcp:setup` asks which AI tools to connect, lists its changes before making them, and writes
  to the user's settings instead of the project. `-Dswing2mcp.clients` and `-Dswing2mcp.scope=project`
  give the answers without questions.
- The Maven group ID is `io.github.ibrahimcavdar` (was `io.github.swing2mcp`); the Java packages moved to
  `io.github.ibrahimcavdar.swing2mcp`.

- MCP Java SDK 2.2.0, gson 2.15.0 and newer build plugins.
- Documentation reorganized: design notes in `docs/design.md`, a manual test checklist in
  `docs/manual-testing.md`, a contributor guide in `CONTRIBUTING.md`.

## 0.1.0 - 2026-11-15

First alpha. Built from source by its users; not on Maven Central.

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
- **Maven plugin:** `swing2mcp:setup` installs the MCP server and writes the settings of Claude Code,
  Cursor, OpenCode and Codex; `swing2mcp:run` starts the project's application with the agent.
- Two demo applications, without and with Spring.
- Tested on Windows 11 and macOS, with applications on Java 8 and Java 21.
