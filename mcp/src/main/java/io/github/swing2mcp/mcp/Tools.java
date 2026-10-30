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
                  "maxRows":{"type":"integer","description":"Rows/items listed per table, list or tree (default 20)"},
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
                Click a component like a user would. Buttons, checkboxes and menu items (even inside \
                closed menus) are activated directly; other components receive real mouse events. \
                For tables, lists and trees pass `row` (and `col` for tables). Use clickCount=2 for \
                double-click, button=right for context menus. Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "ref":{"type":"string"},
                  "row":{"type":"integer","description":"Row (table/tree) or index (list) to click"},
                  "col":{"type":"integer","description":"Table column"},
                  "clickCount":{"type":"integer","description":"2 for double-click"},
                  "button":{"type":"string","enum":["left","right"]},
                  "x":{"type":"integer","description":"Explicit x inside the component"},
                  "y":{"type":"integer","description":"Explicit y inside the component"}
                },"required":["ref"]}""", args -> text(apps.current().post("/click", params(args), DEFAULT_TIMEOUT)));

        tool("type_text", """
                Set the text of a text field, text area, password field, spinner or editable combo box. \
                Replaces the content unless append=true; submit=true also presses Enter (fires the \
                field's action). Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "ref":{"type":"string"},
                  "text":{"type":"string"},
                  "submit":{"type":"boolean"},
                  "append":{"type":"boolean"}
                },"required":["ref","text"]}""", args -> text(apps.current().post("/type", params(args), DEFAULT_TIMEOUT)));

        tool("select_option", """
                Select an entry by visible text (`value`) or position (`index`) in a combo box, list, \
                tab pane, table (row) or tree. For trees, value is a path like "Root/Child/Leaf". \
                Returns the new snapshot.""", """
                {"type":"object","properties":{
                  "ref":{"type":"string"},
                  "value":{"type":"string"},
                  "index":{"type":"integer"}
                },"required":["ref"]}""", args -> text(apps.current().post("/select", params(args), DEFAULT_TIMEOUT)));

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
                .description(description)
                .inputSchema(json, schema)
                .build();
        specs.add(SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                    try {
                        return handler.handle(args);
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
