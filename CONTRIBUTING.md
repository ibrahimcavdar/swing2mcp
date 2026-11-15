# Contributing

Issues and pull requests are welcome. For anything larger than a small fix, please open an issue first
so the approach can be agreed on before you spend time on it.

## Build

`mvn install` at the repository root, with JDK 17 or newer and Maven 3.6.3 or newer.

To try the result, go to `demo-app` or `demo-spring` and run `mvn swing2mcp:setup`, then
`mvn swing2mcp:run` (login: `admin` / `admin`).

## Modules

| Module | Java | Role |
|---|---|---|
| `agent` | 8 | Runs inside the application's JVM |
| `mcp` | 17 | MCP server; embeds the agent jar |
| `maven-plugin` | 8 | `swing2mcp:setup`, `swing2mcp:run` |
| `demo-app`, `demo-spring` | 8 | Test applications; they must not know about swing2mcp |

Why it is built this way: [docs/design.md](docs/design.md).

## Rules

- `agent` and `maven-plugin` must stay Java 8 compatible (`maven.compiler.release=8`).
- `agent` has no dependencies and never references Spring or any other library at compile time. It uses
  only public Swing/AWT API; libraries of the application are reached by reflection.
- In `mcp`, nothing may write to stdout except the MCP protocol.
- Code that runs on the Swing UI thread must never loop on a condition that may not change.
- Code, comments, documentation, commit messages and tool descriptions are in English.

## Testing

There are no automated tests yet. Changes are checked by hand against the demo applications:

- [docs/manual-testing.md](docs/manual-testing.md) is the checklist.
- [scripts/mcp-steps.py](scripts/mcp-steps.py) drives the MCP server without an AI tool: it runs tool
  calls given as JSON lines (description at the top of the file).
- Check with the application on Java 8 as well as on a current JDK (`-Dswing2mcp.javaHome=...`).
- If you change the demo sources while testing hot reload, restore them with
  `git checkout -- demo-app demo-spring` and build again.

## Versions and releases

The poms always say `${revision}`, which is `999-SNAPSHOT`; no commit changes a version. A release is a
tag `vX.Y.Z` pushed to GitHub: the [release workflow](.github/workflows/release.yml) builds it with
`-Drevision=X.Y.Z` and publishes a GitHub release. Notable changes go into [CHANGELOG.md](CHANGELOG.md)
under "Unreleased".

## Good to know

- **The agent jar is locked while an application runs.** On Windows, `mvn install` may fail to replace
  the agent jar in `~/.m2` while an application started with it is open. Close the application first.
- **Attaching to Java 8 looks like a failure.** The attach client of Java 17 reports success on a Java 8
  target as `AgentLoadException: 0` (`Failed to load agent library: 0` on Windows). `Attacher` counts
  both as success.
- **Compile with the same JDK as the running application.** javac of JDK 8 and of JDK 9+ write the flags
  of anonymous classes in a static context differently, so hot reload sees a structural change.
- **Hot reload baseline.** It is recorded in the background when the agent starts. Class files written
  after the JVM started are treated as unknown and applied on the first reload.
- **`java.awt.Robot` (`mode=os`).** On a locked screen its keys silently go nowhere. On JDK 8 for Windows
  it moves the pointer relatively and lands off target with display scaling; `OsMouse.move` measures with
  `MouseInfo` and corrects. Java can report a window as focused while Windows keeps another program in
  front, so never trust Java's focus state alone before pressing real keys or buttons.
- **Testing an inactive window.** A window minimized from another process stays the active window for
  Java. Let the application open and close a dialog afterwards; then its frame is inactive.
- **Plugin and Java 17.** Because the plugin bundles the Java 17 MCP server jar, the Maven plugin tooling
  would mark the plugin as requiring Java 17; `requiredJavaVersion=8` in `maven-plugin/pom.xml` prevents
  that.
- **Windows PowerShell 5.1** reads a `.ps1` file without a byte order mark as ANSI. Keep
  `scripts/windows-manual-test.ps1` saved as UTF-8 with BOM.
