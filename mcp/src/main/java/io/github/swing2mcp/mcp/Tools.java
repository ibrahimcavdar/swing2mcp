package io.github.swing2mcp.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool definitions. Each tool is a thin translation to an agent HTTP endpoint. */
final class Tools {

    @FunctionalInterface
    private interface Handler {
        CallToolResult handle(Map<String, Object> args) throws Exception;
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final String WAITS = "Waits first (up to timeoutMs) until the component is visible, enabled, "
            + "editable where needed, and not blocked by a modal dialog or busy overlay, so no wait_for is needed "
            + "for a component that is about to become usable.";
    private static final String TIMEOUT_PROPERTY =
            "\"timeoutMs\":{\"type\":\"integer\",\"description\":\"How long to wait for the component to become usable (default 10000)\"}";
    private static final String DIALOG_PROPERTY =
            "\"dialog\":{\"type\":\"string\",\"description\":\"Answer the modal dialog this action opens with this button "
            + "(text, or ok/yes/no/cancel); its message is reported. An error if no dialog opens within 3 s\"}";

    private final Apps apps;
    private final McpJsonMapper json;
    private final List<SyncToolSpecification> specs = new ArrayList<>();

    Tools(Apps apps, McpJsonMapper json) {
        this.apps = apps;
        this.json = json;
        define();
    }

    List<SyncToolSpecification> specifications() {
        return specs;
    }

    private void define() {
        tool("launch_app", """
                Start a Java Swing application with the swing2mcp agent injected and connect to it. \
                `command` is a shell command line, e.g. `java -jar app.jar` or a start script; the agent is \
                added through JAVA_TOOL_OPTIONS, so the application itself is not changed. Application output \
                goes to a log file. Returns once the agent is up, usually before the first window: \
                call wait_for next.""", """
                {"type":"object","properties":{
                  "command":{"type":"string","description":"Shell command that starts the application"},
                  "cwd":{"type":"string","description":"Working directory (default: current directory)"},
                  "timeoutSeconds":{"type":"integer","description":"How long to wait for the agent (default 60)"}
                },"required":["command"]}""", args -> {
            Path cwd = args.get("cwd") == null ? null : Path.of(str(args, "cwd"));
            long timeout = num(args, "timeoutSeconds", 60);
            return text(apps.launch(str(args, "command"), cwd, Duration.ofSeconds(timeout)));
        });

        tool("list_apps", """
                List running Java applications. Those with the swing2mcp agent show their windows; \
                the connected one is marked with *. The others can be connected with connect_app, \
                which loads the agent into them without restarting.""", """
                {"type":"object","properties":{}}""", args -> {
            List<Apps.AppInfo> list = apps.list();
            List<Attacher.Jvm> others = apps.otherJvms();
            if (list.isEmpty() && others.isEmpty()) {
                return text("No running Java applications found.");
            }
            StringBuilder sb = new StringBuilder();
            long connected = apps.currentPid().orElse(-1L);
            if (!list.isEmpty()) {
                sb.append("With swing2mcp agent:\n");
            }
            for (Apps.AppInfo app : list) {
                String windows;
                try {
                    String ping = new AgentClient(app.pid(), app.port(), app.token())
                            .get("/ping", Map.of(), Duration.ofSeconds(3));
                    windows = ping.lines().filter(l -> l.startsWith("windows=")).findFirst().orElse("windows=")
                            .substring("windows=".length());
                } catch (Exception e) {
                    windows = "(not responding: " + e.getMessage() + ")";
                }
                sb.append(app.pid() == connected ? "* " : "  ").append("pid ").append(app.pid())
                        .append(" | Java ").append(app.java()).append(" | ").append(app.command())
                        .append(" | windows: ").append(windows.isEmpty() ? "none" : windows).append('\n');
            }
            if (!others.isEmpty()) {
                sb.append(list.isEmpty() ? "" : "\n").append("Other Java processes (no agent yet; connect_app loads it):\n");
                for (Attacher.Jvm jvm : others) {
                    sb.append("  pid ").append(jvm.pid()).append(" | ")
                            .append(jvm.name().isBlank() ? "(unknown main class)" : jvm.name()).append('\n');
                }
            }
            return text(sb.toString());
        });

        tool("connect_app", """
                Connect to a running Java application by pid (see list_apps). If it was started without \
                swing2mcp, the agent is loaded into it first: no restart, and the application keeps its \
                state (open windows, logged-in user). Works with Java 8+ processes of the same OS user.""", """
                {"type":"object","properties":{"pid":{"type":"integer"}},"required":["pid"]}""", args -> {
            String result = apps.connectOrAttach(num(args, "pid", -1));
            return text(result + "\n" + apps.current().get("/ping", Map.of(), Duration.ofSeconds(3)));
        });

        tool("stop_app", "Terminate an application (default: the connected one).", """
                {"type":"object","properties":{"pid":{"type":"integer"}}}""", args -> {
            long pid = args.get("pid") != null ? num(args, "pid", -1) : apps.current().pid;
            return text(apps.stop(pid));
        });

        tool("snapshot", """
                Describe what is on screen: every visible window as an indented outline of components \
                (role, label, value, state) with a [ref=eN] for each element. Use refs with click, \
                type_text, select_option and screenshot. Refs stay valid while the component exists. \
                `field=` is the Java field holding the component, `class=` an application class. \
                Prefer this over screenshot; use screenshot for custom-painted parts and layout.""", """
                {"type":"object","properties":{
                  "window":{"type":"string","description":"Ref of a single window to describe"},
                  "maxRows":{"type":"integer","description":"Rows/items listed per table or list (default 10; trees twice that). read_items reads the rest"},
                  "expandMenus":{"type":"boolean","description":"Also list items of closed menus"}
                }}""", args -> text(apps.current().get("/snapshot", params(args), DEFAULT_TIMEOUT)));

        tool("screenshot", """
                PNG image of a window or component, painted by Swing itself (works even if the window is \
                covered). Without ref: the modal dialog, active window or main frame.""", """
                {"type":"object","properties":{"ref":{"type":"string","description":"Window or component ref"}}}""",
                args -> {
                    byte[] png = apps.current().getBytes("/screenshot", params(args), DEFAULT_TIMEOUT);
                    return CallToolResult.builder()
                            .addContent(new ImageContent(null, Base64.getEncoder().encodeToString(png), "image/png"))
                            .build();
                });

        tool("click", """
                Click a component like a user would, with real mouse press/release events: a focusable \
                component takes the focus first (so the previous field's input verifier and focus-lost \
                handlers run). Menu items, even inside closed menus, are activated directly. \
                For tables, lists and trees pass `value` (the cell's text, the item's text, or a tree \
                path like "Root/Child") or `row` (and `col` for tables). Use clickCount=2 for \
                double-click, button=right for context menus. %WAITS% Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "ref":{"type":"string"},
                  "value":{"type":"string","description":"Table: text of a cell to click (first match; limit with col). List: item text. Tree: node path, ancestors get expanded"},
                  "row":{"type":"integer","description":"Row (table/tree) or index (list) to click"},
                  "col":{"type":"integer","description":"Table column"},
                  "header":{"type":"boolean","description":"Table: click the header of column col (e.g. to sort)"},
                  "clickCount":{"type":"integer","description":"2 for double-click"},
                  "button":{"type":"string","enum":["left","right"]},
                  "x":{"type":"integer","description":"Explicit x inside the component"},
                  "y":{"type":"integer","description":"Explicit y inside the component"},
                  %DIALOG%,
                  %TIMEOUT%
                },"required":["ref"]}""", args -> text(apps.current().post("/click", params(args), actionTimeout(args))));

        tool("type_text", """
                Type into a text field, text area, password field, spinner or editable combo box like a \
                user: the field gets the focus (the previous field's input verifier runs; if it refuses, \
                nothing is typed and the result says why), the old text is selected and each character is \
                sent as real key events, so key listeners, document filters and formatters run. Replaces \
                the content unless append=true; submit=true then presses Enter. "\\n" types Enter and \
                "\\t" types Tab (moves to the next field). Formatted fields and spinners commit on Enter \
                or when the focus leaves them. The application window does not need to be in front. \
                mode=set sets the text directly without key events (faster, but skips key listeners). \
                %WAITS% Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "ref":{"type":"string"},
                  "text":{"type":"string"},
                  "submit":{"type":"boolean","description":"Press Enter afterwards"},
                  "append":{"type":"boolean","description":"Keep the current text and type at its end"},
                  "mode":{"type":"string","enum":["keys","set","os"],"description":"keys (default): key events inside the application; set: set the text directly; os: real keyboard through the operating system (brings the window to the front; rarely needed)"},
                  %DIALOG%,
                  %TIMEOUT%
                },"required":["ref","text"]}""", args -> text(apps.current().post("/type", params(args), actionTimeout(args))));

        tool("press_key", """
                Press keys like a user: "Enter", "Escape", "Tab", "Shift+Tab", "Up", "F5", "Ctrl+S", \
                "Alt+F4", or several separated by spaces ("Tab Tab Enter"). Keys go to the component \
                that has the keyboard focus (marked "focused" in the snapshot), or to ref after focusing \
                it, so key bindings, menu shortcuts, the default button and focus traversal work as for a \
                user. Without ref, keys go to the open modal dialog if there is one. The application \
                window does not need to be in front. The result says where the focus is afterwards. \
                %WAITS% Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "key":{"type":"string","description":"Key or chord, e.g. Enter, Escape, Ctrl+S, Shift+Tab, F2; several separated by spaces"},
                  "ref":{"type":"string","description":"Component to focus first (default: the focused component)"},
                  "mode":{"type":"string","enum":["keys","os"],"description":"keys (default): key events inside the application; os: real keyboard through the operating system (brings the window to the front)"},
                  %DIALOG%,
                  %TIMEOUT%
                },"required":["key"]}""", args -> text(apps.current().post("/key", params(args), actionTimeout(args))));

        tool("select_option", """
                Select an entry by visible text (`value`) or position (`index`) in a combo box, list, \
                tab pane, table or tree. Table: value is the text of any cell of the row (limit it to a \
                column with col); the cell is selected as by a click. Tree: value is a path like \
                "Root/Child/Leaf" (the root may be left out; " > " also separates); ancestors are expanded \
                on the way, as a user would. %WAITS% Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "ref":{"type":"string"},
                  "value":{"type":"string"},
                  "index":{"type":"integer","description":"Position; for tables the row, for trees the visible row"},
                  "col":{"type":"integer","description":"Table column to search in or select"},
                  %DIALOG%,
                  %TIMEOUT%
                },"required":["ref"]}""", args -> text(apps.current().post("/select", params(args), actionTimeout(args))));

        tool("select_menu", """
                Choose a menu item like a user: path such as "File > Export > PDF" in the window's menu \
                bar. Menus are opened level by level, so code that fills or enables items when a menu \
                opens runs; a disabled item is reported, not clicked. A path ending at a menu leaves it \
                open. With context=true, ref is right-clicked and the path is chosen in the context menu \
                that opens (without ref: in the context menu already open). ref without context picks \
                the menu bar of that component's window or internal frame. %WAITS% Returns the new \
                snapshot.""", """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Menu texts separated by >, e.g. File > Open"},
                  "ref":{"type":"string","description":"Component to right-click (context=true), or one inside the window whose menu bar to use"},
                  "context":{"type":"boolean","description":"Use the context (right-click) menu of ref"},
                  %DIALOG%,
                  %TIMEOUT%
                },"required":["path"]}""", args -> text(apps.current().post("/menu", params(args), actionTimeout(args))));

        tool("handle_dialog", """
                Answer a dialog like a user: press one of its buttons by text or by role (ok, yes, no,                 cancel; open/save in file choosers; these work in any UI language), "close" for the                 window's close box. `text` first types the answer of an input dialog (or picks it in its                 combo box); `path` first puts a file or directory path into a file chooser and, unless                 button says otherwise, presses its Open/Save button. Without ref: the open modal dialog                 (or the newest dialog). The result repeats the dialog's title and message. To answer a                 dialog that an action will open, pass dialog=<button> to that action instead.                 %WAITS% Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "button":{"type":"string","description":"Button text, or ok/yes/no/cancel/open/save, or close"},
                  "text":{"type":"string","description":"Answer for an input dialog"},
                  "path":{"type":"string","description":"File or directory for a file chooser"},
                  "ref":{"type":"string","description":"The dialog (or any component in it); default: the open modal dialog"},
                  %TIMEOUT%
                }}""", args -> text(apps.current().post("/dialog", params(args), actionTimeout(args))));

        tool("read_items", """
                Read the contents of a table, list, tree or combo box, page by page, as the user sees \
                them (the text the cells show, with the underlying value where it reads differently). \
                The snapshot shows only the first rows. Table: rows from `start` (view order, as \
                sorted/filtered on screen), optionally only some `columns`. Tree: without path, the \
                visible rows; with `path`, that node and its descendants down to `depth` levels, read \
                from the tree model without expanding anything (trees that load children on expand \
                need expand_node first). `find` keeps only entries containing that text.""", """
                {"type":"object","properties":{
                  "ref":{"type":"string"},
                  "start":{"type":"integer","description":"First row/item to return (default 0); with find in a table, list or combo: matches to skip"},
                  "limit":{"type":"integer","description":"How many to return (default 50, max 1000)"},
                  "find":{"type":"string","description":"Only entries containing this text (case-insensitive)"},
                  "columns":{"type":"string","description":"Table: column names or indexes, comma-separated"},
                  "path":{"type":"string","description":"Tree: node path like Root/Child"},
                  "depth":{"type":"integer","description":"Tree with path: levels below the node (default 1)"}
                },"required":["ref"]}""", args -> text(apps.current().get("/read", params(args), DEFAULT_TIMEOUT)));

        tool("expand_node", """
                Expand (or collapse) a tree node given by its path, like clicking its toggle: ancestors \
                are expanded on the way and expansion listeners run, so trees that load children on \
                expand fill in. Says if the application vetoed it. %WAITS% Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "ref":{"type":"string","description":"The tree"},
                  "path":{"type":"string","description":"Node path like Root/Child (the root may be left out)"},
                  "collapse":{"type":"boolean","description":"Collapse instead of expand"},
                  %TIMEOUT%
                },"required":["ref","path"]}""", args -> text(apps.current().post("/expand", params(args), actionTimeout(args))));

        tool("wait_for", """
                Wait until `text` appears anywhere in the snapshot (or disappears with gone=true). \
                Without text, waits for the first window to appear, which is useful right after \
                launch_app. Returns the snapshot.""", """
                {"type":"object","properties":{
                  "text":{"type":"string"},
                  "gone":{"type":"boolean"},
                  "timeoutMs":{"type":"integer","description":"Default 10000"}
                }}""", args -> {
            long timeoutMs = num(args, "timeoutMs", 10000);
            return text(apps.current().get("/wait", params(args), Duration.ofMillis(timeoutMs + 10000)));
        });

        tool("hot_reload", """
                Apply code changes to the running application without restarting it. Workflow: edit the \
                Java sources, compile them (e.g. `mvn -q compile` in the application's module, or let the \
                IDE build), then call hot_reload. Changed classes are found in the class directories \
                (target/classes) and loaded into the running JVM. If only event handlers, painting or \
                business logic changed, open screens keep their state; if a screen's construction code \
                changed (constructor, initComponents, @PostConstruct...), that screen is rebuilt. Changes \
                that add or remove methods or fields (including new lambdas) cannot be applied on a \
                standard JVM: the result explains which, and nothing is changed; then restart the \
                application. Only classes whose code really changed are reloaded, even if the build \
                rewrote all class files. In a multi-module project, a module that the application runs \
                from its jar needs classesDir (or start the application with \
                mvn swing2mcp:run -Dswing2mcp.classesDirs=...). Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "classesDir":{"type":"string","description":"Directory with compiled classes, if the application was not started from it (e.g. a module that runs from its jar)"},
                  "rebuild":{"type":"string","enum":["auto","none","all"],"description":"auto (default): rebuild screens whose construction code changed; all: rebuild every open screen of a reloaded class; none: never"},
                  "refs":{"type":"array","items":{"type":"string"},"description":"Refs of screens to rebuild in any case"}
                }}""", args -> {
            Map<String, Object> form = params(args);
            if (args.get("refs") instanceof List<?> list) {
                form.put("refs", String.join(",", list.stream().map(String::valueOf).toList()));
            }
            return text(apps.current().post("/reload", form, DEFAULT_TIMEOUT));
        });

        tool("spring_beans", """
                For Spring applications: list the beans of the application's Spring context, which is found \
                automatically without any change to the application. Window and panel beans are listed \
                first and can be shown with open_spring_bean. Fails politely if the application has no \
                Spring.""", """
                {"type":"object","properties":{
                  "filter":{"type":"string","description":"Only beans whose name or class contains this text"}
                }}""", args -> text(apps.current().get("/spring/beans", params(args), DEFAULT_TIMEOUT)));

        tool("open_spring_bean", """
                For Spring applications: show a window or panel bean directly instead of navigating menus. \
                Windows and dialogs are opened (prototype beans give a fresh instance); a panel that is not \
                on screen yet is shown in a new frame; a panel in a hidden tab gets its tab selected. \
                Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Bean name from spring_beans"}
                },"required":["name"]}""", args -> text(apps.current().post("/spring/open", params(args), DEFAULT_TIMEOUT)));
    }

    private void tool(String name, String description, String schema, Handler handler) {
        Tool tool = Tool.builder()
                .name(name)
                .description(description.replace("%WAITS%", WAITS))
                .inputSchema(json, schema.replace("%TIMEOUT%", TIMEOUT_PROPERTY).replace("%DIALOG%", DIALOG_PROPERTY))
                .build();
        specs.add(SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                    try {
                        return handler.handle(args);
                    } catch (AgentClient.AppExited e) {
                        // e.g. Exit was clicked or Alt+F4 closed the main window: not a failure
                        return text(e.getMessage() + " Use list_apps or launch_app to continue.");
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .addTextContent(e.getMessage() == null ? e.toString() : e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build());
    }

    private static CallToolResult text(String text) {
        return CallToolResult.builder().addTextContent(text).build();
    }

    /** Tool arguments as agent form parameters; JSON numbers arrive as Integer, Long or Double. */
    private static Map<String, Object> params(Map<String, Object> args) {
        Map<String, Object> result = new LinkedHashMap<>();
        args.forEach((key, value) -> {
            if (value instanceof Number n) {
                result.put(key, n.longValue());
            } else if (value != null) {
                result.put(key, value);
            }
        });
        return result;
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        return value.toString();
    }

    /** Room for the wait before the action plus the action itself. */
    private static Duration actionTimeout(Map<String, Object> args) {
        // typing sends several UI events per character; long texts need more time
        int typed = String.valueOf(args.getOrDefault("text", "")).length() + String.valueOf(args.getOrDefault("key", "")).length();
        Duration needed = Duration.ofMillis(num(args, "timeoutMs", 10000)).plusSeconds(20).plusMillis(100L * typed);
        return needed.compareTo(DEFAULT_TIMEOUT) > 0 ? needed : DEFAULT_TIMEOUT;
    }

    private static long num(Map<String, Object> args, String key, long def) {
        Object value = args.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString().trim());
    }
}
