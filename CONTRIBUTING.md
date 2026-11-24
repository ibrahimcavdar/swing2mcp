# Contributing

Bug reports and ideas are the most useful contributions right now: please open an issue.

Pull requests with small fixes are welcome too. For anything larger, open an issue first so the approach
can be agreed on before you spend time on it; this is a one-person project and not every change can be
taken on. Contributions are accepted under the project's license, Apache-2.0.

## Build

`mvn install` at the repository root, with JDK 17 or newer and Maven 3.6.3 or newer.

To try the result, go to `demo-app` or `demo-spring` and run `mvn swing2mcp:setup`, then
`mvn swing2mcp:run` (login: `admin` / `admin`).

## Modules

| Module | Java | Role |
|---|---|---|
| `agent` | 8 | Runs inside the application's JVM |
| `test-library` | 8 | Test library (`swing2mcp-test`), no dependencies; also the code that finds, starts and talks to applications, and the embedded agent jar |
| `mcp` | 8 | MCP server, no dependencies; the build copies the connection code and the agent jar from `test-library` into its jar |
| `maven-plugin` | 8 | `swing2mcp:setup`, `swing2mcp:run`, `swing2mcp:uninstall` |
| `demo-app`, `demo-spring` | 8 | Test applications; they must not know about swing2mcp |
| `e2e-tests` | 17 | End-to-end tests; not installed or published |

Why it is built this way: [docs/design.md](docs/design.md).

## Rules

- `agent`, `test-library`, `mcp` and `maven-plugin` must stay Java 8 compatible (`maven.compiler.release=8`). Building
  the project itself needs JDK 17 or newer, because of the tests.
- `agent` has no dependencies and never references Spring or any other library at compile time. It uses
  only public Swing/AWT API; libraries of the application are reached by reflection.
- In `mcp`, nothing may write to stdout except the MCP protocol.
- Code that runs on the Swing UI thread must never loop on a condition that may not change.
- Code, comments, documentation, commit messages and tool descriptions are in English.

## Testing

`mvn install` (or `mvn verify`) runs the end-to-end tests in `e2e-tests`. They start the built MCP server
jar and use `demo-app` through it, exactly as an AI tool does: the application window opens for a few
seconds. On a machine without a display they are skipped; on Linux, `xvfb-run mvn install` gives them
one. `-DskipTests` leaves them out.

They cover the basics so far (start, attach, login, a form, a dialog, a screenshot). Everything else is
still checked by hand against the demo applications:

- [docs/manual-testing.md](docs/manual-testing.md) is the checklist.
- When you fix a bug or add a tool, add a test for it if the demo application can show it.
- [scripts/mcp-steps.py](scripts/mcp-steps.py) drives the MCP server without an AI tool: it runs tool
  calls given as JSON lines (description at the top of the file).
- Check with the application on Java 8 as well as on a current JDK (`-Dswing2mcp.javaHome=...`).
- If you change the demo sources while testing hot reload, restore them with
  `git checkout -- demo-app demo-spring` and build again.

## Versions and releases

The poms always say `${revision}`, which is `999-SNAPSHOT`; no commit changes a version. A release is a
tag `vX.Y.Z` pushed to GitHub: the [release workflow](.github/workflows/release.yml) builds and tests it
with `-Drevision=X.Y.Z`, signs it, uploads it to Maven Central and creates a GitHub release. The upload
waits in the Central Portal until the maintainer publishes it there; a published version can never be
changed or removed. `mvn -Prelease -Dgpg.skip=true -DskipUpload=true clean deploy` builds the same
artifacts locally without uploading anything. Notable changes go into [CHANGELOG.md](CHANGELOG.md)
under "Unreleased".

## Good to know

- **The agent jar is locked while an application runs.** On Windows, `mvn install` may fail to replace
  the agent jar in `~/.m2` while an application started with it is open. Close the application first.
- **Attaching to Java 8 looks like a failure.** The attach client of a newer Java reports success on a Java 8
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
- **Windows PowerShell 5.1** reads a `.ps1` file without a byte order mark as ANSI. Keep
  `scripts/windows-manual-test.ps1` saved as UTF-8 with BOM.
