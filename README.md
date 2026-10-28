# swing2mcp

An MCP server that lets AI tools such as Claude Code **see and use** legacy Java Swing
applications without touching their code. A Playwright-like experience for Swing.

```
Claude Code ──MCP(stdio)──▶ swing2mcp.jar ──localhost HTTP──▶ agent (inside the application's JVM)
```

- The application does not change: the agent is added at startup via `JAVA_TOOL_OPTIONS=-javaagent:...`.
- Target application: Java 8+ (tested with 8 and 21). The bridge needs Java 17+.
- The screen state is captured as text (`snapshot`) and as PNG (`screenshot`). The
  `[ref=eN]` values in the snapshot are used to click, type and select.

## Building

Requires JDK 17+ and Maven:

```bash
mvn package
```

Outputs:

| File | What |
|---|---|
| `mcp/target/swing2mcp.jar` | MCP server (agent embedded). The only file the user needs. |
| `agent/target/swing2mcp-agent.jar` | The agent itself (for manual use with `-javaagent`) |
| `demo-app/target/demo-app.jar` | Java 8 demo application (login: `admin` / `admin`) |

## Adding it to Claude Code

```bash
claude mcp add swing -- java -jar /full/path/swing2mcp/mcp/target/swing2mcp.jar
```

This `java` must be version 17 or later. The application itself can run on a different Java
(e.g. 8); that is specified in the `launch_app` command.

Example request: *"Start demo-app.jar with Java 8, log in with admin/admin and add a customer
named Ali Veli from İzmir."*

## Tools

| Tool | Description |
|---|---|
| `launch_app` | Starts the application with the agent attached (`java -jar ...`, `.bat`/`.sh` script) |
| `list_apps` / `connect_app` | Lists running applications that have the agent / selects one |
| `stop_app` | Closes the application |
| `snapshot` | Text summary of the windows, with `ref` IDs |
| `screenshot` | PNG image of a window or component |
| `click` | Button, menu item, table row; double click, right click |
| `type_text` | Text field, password, spinner, editable combo |
| `select_option` | Combo, list, tab, table row, tree node |
| `wait_for` | Waits for a text to appear or disappear |

Example snapshot:

```
- frame "ACME Customer Manager 3.2 - admin" [ref=e10] 900x620 active class=MainFrame
  - menubar [ref=e11]
    - menu "File" [ref=e12] items=3 (closed)
  - tabs [ref=e15] tabs=["Customers"(selected), "Orders", "Reports"] field=tabs
    - panel CustomerPanel [ref=e16] field=customerPanel
      - group "Customer Details" [ref=e17]
        - textfield "Full Name" [ref=e18] value="" field=txtName
        - combobox "City" [ref=e20] value="Ankara" options=["Ankara", "İstanbul", ...] field=cmbCity
        - button "Save" [ref=e23] field=btnSave
      - table [ref=e26] rows=3 cols=5 field=tblCustomers
        - columns: ID | Full Name | Email | City | Active
        - row 0: 1 | Ayşe Yılmaz | ayse@example.com | Ankara | [x]
```

`field=` is the name of the Java field that holds the component in the application. It makes matching with the code easier.

## Using the agent by hand

```bash
java -javaagent:swing2mcp-agent.jar -jar product.jar
```

The agent listens only on `127.0.0.1`, with a random port and token. It writes the connection details
to `~/.swing2mcp/jvms/<pid>.properties` (readable only by its owner) and deletes the file when the
application exits.

## Development

See [DESIGN.md](DESIGN.md) for the project structure and decisions.
