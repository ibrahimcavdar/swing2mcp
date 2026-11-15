# Manual test checklist

There are no automated tests yet; this is the checklist to go through after a change. It uses the two
demo applications. The commands are written for Windows `cmd.exe` (the main target platform); on macOS
and Linux only the paths differ.

## Requirements

| What | Why |
|---|---|
| JDK 17+ (a JDK, not a JRE) | Builds the project and runs the MCP server; attaching to an open application needs a JDK |
| Maven 3.6.3+ | `mvn install`, `mvn swing2mcp:setup`, `mvn swing2mcp:run` |
| JDK 8 (optional) | Runs the demo applications on Java 8, close to the real target |
| An AI tool, or Python 3.7+ | Claude Code, Cursor, Codex or OpenCode; or [scripts/mcp-steps.py](../scripts/mcp-steps.py) to call the tools by hand |

## 1. Build

```bat
mvn install
```

- [ ] `BUILD SUCCESS` for five modules.

## 2. Connect the AI tool

```bat
cd demo-app
mvn swing2mcp:setup
```

- [ ] The output names the installed MCP server jar under `.swing2mcp\bin` in the user folder.
- [ ] `.mcp.json`, `.cursor\mcp.json`, `opencode.json` and `.codex\config.toml` exist in `demo-app`, with
  correct absolute paths.
- [ ] With Maven on Java 8, the error message suggests `-Dswing2mcp.mcpJavaHome=...`, and it works with
  that parameter.

Open the AI tool in the `demo-app` folder. Claude Code asks to approve the project's server on first
start; Codex reads project settings only in trusted projects.

## 3. Run and use the application

In a separate terminal:

```bat
cd demo-app
mvn swing2mcp:run
```

On Java 8: `mvn swing2mcp:run -Dswing2mcp.javaHome="C:\Program Files\Java\jdk1.8.0_xxx"`

Prompt:

> connect to the swing application, log in with admin/admin, add a customer named Ali Veli from İzmir,
> check that it shows up in the table and take a screenshot.

- [ ] The customer is in the table.
- [ ] Non-ASCII characters in the sample data (İzmir, Ayşe Yılmaz) are correct in the snapshot and in the
  screenshot.
- [ ] With display scaling at 125% or 150%, the screenshot is sharp and not cut off.
- [ ] Ctrl+C in the Maven terminal closes the application too.

## 4. Connect to an application that is already open

Start it without swing2mcp:

```bat
cd demo-app
java -jar target\demo-app.jar
```

Log in and type something into the form without saving. Prompt:

> list the open Java applications, connect to the demo application and tell me what is written on the screen.

- [ ] `list_apps` shows the application under "no agent yet"; `connect_app` connects; the typed text is
  still there.
- [ ] If it cannot connect, antivirus or a corporate policy may be blocking attach. Note the error message.

## 5. Spring

```bat
cd demo-spring
mvn swing2mcp:setup
mvn swing2mcp:run
```

> log in, list the screens in the application and open the Settings screen without touching the menu.

- [ ] `spring_beans` returns the list; `open_spring_bean settingsDialog` opens Settings.

## 6. Hot reload

Open `demo-app` with `mvn swing2mcp:run`, log in and type a name into the form.

> in CustomerPanel, change the status message written after saving to "Save completed: ",
> compile with `mvn -q compile`, apply it to the running application with hot_reload, then press Save to try it.
> Do not restart the application.

- [ ] `hot_reload` reports that `saveCustomer` changed and that no screen needed rebuilding; the typed
  name is still there; after Save the new message shows up.

> now change the text of the Clear button to "Clear Form", compile and apply it with hot_reload.

- [ ] `CustomerPanel` is rebuilt in place by calling `initComponents()` again; the new text shows up; the
  buttons work.

> add a new helper method to CustomerPanel and use it, compile and try hot_reload.

- [ ] It is rejected without being applied, with a "method added ..." explanation.

Restore the demo sources afterwards: `git checkout -- demo-app demo-spring` in the repository root.

## 7. Like a real user

With `demo-app` open and logged in:

- [ ] Keys: a prompt that logs in with `type_text`, Tab and Enter works; so do shortcuts (Ctrl+N).
- [ ] Validation: letters typed into the phone field are rejected and the result says so; an invalid
  email keeps the focus and the result gives the reason.
- [ ] The same works while another program is in front.
- [ ] Tables and trees: `read_items` pages through the invoice table; a tree node is selected by its path.
- [ ] Menus and dialogs: a menu path ("File > ..."), Delete answered with `dialog=yes`, the file chooser.
- [ ] Planner tab: several list items selected, items dragged to the other list, the map panned, the
  slider and the split pane divider moved.

## Checks that need a person at the screen (Windows)

Run `mvn install` first. Tools that are not on `PATH` are also looked up under `%USERPROFILE%\tools`.

| Command | What it does | What you do |
|---|---|---|
| `powershell -ExecutionPolicy Bypass -File scripts\windows-manual-test.ps1 ctrlc` | Opens demo-app with `mvn swing2mcp:run` | Press Ctrl+C in the terminal when the window opens. The script prints PASSED or FAILED |
| `powershell -ExecutionPolicy Bypass -File scripts\windows-manual-test.ps1 screenshot` | Opens the application on Java 8 and on the server's JDK, logs in and saves screenshots under `demo-app\target\manual-test` | Compare the images with the windows on the screen, then press Enter |
| `powershell -ExecutionPolicy Bypass -File scripts\windows-manual-test.ps1 ai` | Writes the AI tool settings, copies the first prompt to the clipboard and opens the application | Open the AI tool in `demo-app` and paste the prompt |

If JDK 8 is somewhere else: `... screenshot -Java8Home "C:\path\jdk8"`.

## Reporting a problem

- Which step, which version or commit, which AI tool.
- The full error message as the AI tool shows it; for Maven errors the output of `mvn -e ...`.
- The latest file under `.swing2mcp\logs` in the user folder (for applications started with `launch_app`).
- `java -version` of both the Java that runs the MCP server and the one that runs the application.
- Whether the user folder path contains a space or non-ASCII characters.
