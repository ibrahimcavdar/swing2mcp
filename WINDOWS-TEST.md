# Windows test guide

Goal: to see that the flows that worked on macOS throughout M1–M5 also work on Windows. If a problem
comes up, going back to M4 and trying the same step again tells whether the problem comes from hot reload (M5) or from
earlier.

The milestones are marked as git tags: `m1`, `m2`, `m3`, `m4`, `m5`.

## 0. Requirements

| What | Why |
|---|---|
| Git | To get the repository and switch between milestones |
| **JDK 17+** (a JDK, not a JRE) | To build the project and run the MCP server. Connecting to an open application later (attach) needs a JDK. |
| Maven 3.6.3+ | `mvn install`, `mvn swing2mcp:setup`, `mvn swing2mcp:run` |
| JDK 8 (optional) | To run the demo application on Java 8 and test close to the real target |
| An AI tool | Claude Code, Cursor, Codex or OpenCode |

The commands are for `cmd.exe`. If you use PowerShell, write `$env:X="Y"` instead of `set X=Y`.

## 1. Getting the repository

The repository is on GitHub (private): https://github.com/ibrahimcavdar/swing2mcp

```bat
git clone https://github.com/ibrahimcavdar/swing2mcp.git
cd swing2mcp
```

The `main` branch contains the M5 code and these documents (the `m5` tag shows the same code, at the commit
before the documents).

Since it is private, the work computer needs a GitHub login (`gh auth login` or an SSH key).
If there is no access to GitHub: in the repository root on the Mac, create a single file with `git bundle create swing2mcp.bundle --all`,
copy it over and open it with `git clone swing2mcp.bundle swing2mcp`.

If you will continue in this repository with an AI agent, first have it read [docs/SESSION-SUMMARY.md](docs/SESSION-SUMMARY.md)
(Claude Code finds it by itself through `CLAUDE.md`).

## 2. Building

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-17
mvn install
```

Expected: `BUILD SUCCESS`, five modules (agent, MCP server, Maven plugin, demo app, demo app Spring).

## 3. Connecting the AI tool (M2)

```bat
cd demo-app
mvn swing2mcp:setup
```

Check:

- [ ] The output has `MCP server installed at C:\Users\...\.swing2mcp\bin\swing2mcp-0.1.0-SNAPSHOT.jar`.
- [ ] `.mcp.json`, `.cursor\mcp.json`, `opencode.json` and `.codex\config.toml` were created in the `demo-app` folder
  and the paths in them are correct, in the `C:\...` form.
- [ ] If Maven runs on Java 8, the error message should suggest `-Dswing2mcp.mcpJavaHome=...`; try
  again with that parameter.

Open the AI tool you use in the `demo-app` folder. Claude Code asks you to approve the project server on first start;
Codex reads the project settings only in trusted projects.

## 4. Running and using the application (M1, M2)

In a separate terminal:

```bat
cd demo-app
mvn swing2mcp:run
```

To try it with Java 8: `mvn swing2mcp:run -Dswing2mcp.javaHome="C:\Program Files\Java\jdk1.8.0_xxx"`

Example prompt for the AI tool:

> connect to the swing application, log in with admin/admin, add a customer named Ali Veli from İzmir,
> check that it shows up in the table and take a screenshot.

Check:

- [ ] Non-ASCII characters in the sample data (İzmir, Ayşe Yılmaz) are correct in the snapshot and in the screenshot.
- [ ] With display scaling at 125%/150%, the screenshot looks right (not blurry or cut off).
- [ ] Ctrl+C in the Maven terminal → the application closes too.

## 5. Connecting to an open application later (M3)

Open it normally, without swing2mcp:

```bat
cd demo-app
java -jar target\demo-app.jar
```

Log in and type something into the form (do not save). Tell the AI tool:

> list the open Java applications, connect to the demo application and tell me what is written on the screen.

- [ ] `list_apps` shows the application under "no agent yet"; `connect_app` connects; the text you typed
  is still there.
- [ ] If it cannot connect: antivirus/corporate policy may be blocking attach. Write down the error message.

## 6. Spring (M4)

```bat
cd ..\demo-spring
mvn swing2mcp:setup
mvn swing2mcp:run
```

> log in, list the screens in the application and open the Settings screen without touching the menu.

- [ ] `spring_beans` returns the list, `open_spring_bean settingsDialog` opens Settings.

## 7. Hot reload (M5)

Open `demo-app` with `mvn swing2mcp:run`, log in and type a name into the form. Tell the AI tool:

> in CustomerPanel, change the status message written after saving to "Save completed: ",
> compile with `mvn -q compile`, apply it to the running application with hot_reload, then press Save to try it.
> Do not restart the application.

- [ ] `hot_reload` says "changed saveCustomer" and "No screen needed rebuilding"; the name you typed
  is still there; after Save the new message shows up.

> now change the text of the Clear button to "Clear Form", compile and apply it with hot_reload.

- [ ] `CustomerPanel` "rebuilt in place by calling initComponents() again"; the new text shows up;
  the buttons work.

> add a new helper method to CustomerPanel and use it, compile and try hot_reload.

- [ ] It is rejected without being applied, with a "method added ..." explanation.

Note: to restore the demo sources after the test, run `git checkout -- demo-app demo-spring` in the repository root.

To try the tools by hand without an AI client: [scripts/mcp-steps.py](scripts/mcp-steps.py) (Python 3.7+).

## 8. With a real company application (optional)

1. Add the plugin to the application's pom (the block in the README). A local `mvn install` is needed first.
2. `mvn swing2mcp:setup`, then `mvn swing2mcp:run`.
   - If the main class is not found: `-Dswing2mcp.mainClass=com.example.MainClass`.
   - If the application needs special JVM options: `-Dswing2mcp.jvmArgs="-Xmx1g -Dprofile=dev"`.
3. Or open the application as usual and connect to it later as in M3.

## If something goes wrong: going back to M4

```bat
git checkout m4
mvn install
cd demo-app
mvn swing2mcp:setup
```

Close and reopen the AI tool (so the server restarts) and try the same step again. Then, to go back to `m5`,
run `git checkout main`, `mvn install`, `mvn swing2mcp:setup`.

- If it also happens on M4: the problem is Windows-specific or older.
- If it happens only on M5: it comes from the hot reload changes.

## What to collect for feedback

- Which step, which milestone (`git describe --tags`), which AI tool.
- The full error message (the tool output shown by the AI tool).
- The `mvn -e ...` output (for errors on the Maven side).
- The latest file under `%USERPROFILE%\.swing2mcp\logs\` (for applications opened with `launch_app`).
- `java -version` (both the Java that runs the MCP server and the one that runs the application).
- Whether the user folder path contains a space or non-ASCII (e.g. Turkish) characters.
