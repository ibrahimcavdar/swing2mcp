package io.github.ibrahimcavdar.swing2mcp.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP tools. What the AI tool reads about them (names, descriptions, argument schemas) is data, in
 * the resource {@code swing2mcp/tools.json}; here each tool is a thin translation to an agent HTTP
 * endpoint.
 */
final class Tools {

    private interface Handler {
        Result handle(Map<String, Object> args) throws Exception;
    }

    /** What a tool answers: text, or an image. */
    private static final class Result {
        final String text;
        final byte[] png;
        final boolean error;

        Result(String text, byte[] png, boolean error) {
            this.text = text;
            this.png = png;
            this.error = error;
        }
    }

    private static final String DEFINITIONS = "/swing2mcp/tools.json";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Apps apps;
    private final List<Object> definitions;
    private final Map<String, List<Object>> required = new LinkedHashMap<String, List<Object>>();
    private final Map<String, Handler> handlers = new LinkedHashMap<String, Handler>();

    Tools(Apps apps) throws IOException {
        this.apps = apps;
        this.definitions = load();
        define();
        for (Object definition : definitions) {
            Map<?, ?> tool = (Map<?, ?>) definition;
            String name = (String) tool.get("name");
            if (!handlers.containsKey(name)) {
                throw new IllegalStateException(DEFINITIONS + " describes the tool " + name + ", which is not implemented");
            }
            Object needs = ((Map<?, ?>) tool.get("inputSchema")).get("required");
            required.put(name, needs instanceof List ? new ArrayList<Object>((List<?>) needs)
                    : Collections.<Object>emptyList());
        }
        for (String name : handlers.keySet()) {
            if (!required.containsKey(name)) {
                throw new IllegalStateException("The tool " + name + " is not described in " + DEFINITIONS);
            }
        }
    }

    private static List<Object> load() throws IOException {
        InputStream in = Tools.class.getResourceAsStream(DEFINITIONS);
        if (in == null) {
            throw new IOException("Missing resource " + DEFINITIONS);
        }
        Object parsed = Json.parse(new String(AgentClient.readAll(in), StandardCharsets.UTF_8));
        if (!(parsed instanceof List)) {
            throw new IOException(DEFINITIONS + " must be a JSON array of tools");
        }
        @SuppressWarnings("unchecked")
        List<Object> tools = (List<Object>) parsed;
        return tools;
    }

    /** The answer to tools/list. */
    List<Object> definitions() {
        return definitions;
    }

    boolean has(String name) {
        return handlers.containsKey(name);
    }

    /** Runs a tool and returns the MCP result: its content and whether it is an error. */
    Map<String, Object> call(String name, Map<String, Object> args) {
        Result result;
        try {
            for (Object key : required.get(name)) {
                Object value = args.get(key);
                if (value == null || value.toString().trim().length() == 0) {
                    throw new IllegalArgumentException("Missing argument: " + key);
                }
            }
            result = handlers.get(name).handle(args);
        } catch (AgentClient.AppExited e) {
            // e.g. Exit was clicked or Alt+F4 closed the main window: not a failure
            result = text(e.getMessage() + " Use list_apps or launch_app to continue.");
        } catch (Exception e) {
            result = new Result(e.getMessage() == null ? e.toString() : e.getMessage(), null, true);
        }
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        if (result.png != null) {
            content.put("type", "image");
            content.put("data", Base64.getEncoder().encodeToString(result.png));
            content.put("mimeType", "image/png");
        } else {
            content.put("type", "text");
            content.put("text", result.text);
        }
        Map<String, Object> answer = new LinkedHashMap<String, Object>();
        answer.put("content", Collections.singletonList(content));
        answer.put("isError", result.error);
        return answer;
    }

    private void define() {
        handlers.put("launch_app", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                Path cwd = args.get("cwd") == null ? null : Paths.get(str(args, "cwd"));
                long timeout = num(args, "timeoutSeconds", 60);
                return text(apps.launch(str(args, "command"), cwd, Duration.ofSeconds(timeout)));
            }
        });
        handlers.put("list_apps", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                return text(listApps());
            }
        });
        handlers.put("connect_app", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                String result = apps.connectOrAttach(num(args, "pid", -1));
                return text(result + "\n" + apps.current().get("/ping", null, Duration.ofSeconds(3)));
            }
        });
        handlers.put("stop_app", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                long pid = args.get("pid") != null ? num(args, "pid", -1) : apps.current().pid;
                return text(apps.stop(pid));
            }
        });
        handlers.put("screenshot", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                return new Result(null, apps.current().getBytes("/screenshot", params(args), DEFAULT_TIMEOUT), false);
            }
        });
        handlers.put("select_option", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                Map<String, Object> form = params(args);
                joined(args, form, "values", "\n");
                joined(args, form, "indexes", ",");
                return text(apps.current().post("/select", form, actionTimeout(args)));
            }
        });
        handlers.put("wait_for", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                long timeoutMs = num(args, "timeoutMs", 10000);
                return text(apps.current().get("/wait", params(args), Duration.ofMillis(timeoutMs + 10000)));
            }
        });
        handlers.put("hot_reload", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                Map<String, Object> form = params(args);
                joined(args, form, "refs", ",");
                return text(apps.current().post("/reload", form, DEFAULT_TIMEOUT));
            }
        });
        get("snapshot", "/snapshot");
        get("read_items", "/read");
        get("spring_beans", "/spring/beans");
        action("click", "/click");
        action("type_text", "/type");
        action("press_key", "/key");
        action("select_menu", "/menu");
        action("drag", "/drag");
        action("handle_dialog", "/dialog");
        action("expand_node", "/expand");
        handlers.put("open_spring_bean", new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                return text(apps.current().post("/spring/open", params(args), DEFAULT_TIMEOUT));
            }
        });
    }

    /** A tool that only reads from the application. */
    private void get(String name, final String endpoint) {
        handlers.put(name, new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                return text(apps.current().get(endpoint, params(args), DEFAULT_TIMEOUT));
            }
        });
    }

    /** A tool that does something in the application; the agent first waits until that is possible. */
    private void action(String name, final String endpoint) {
        handlers.put(name, new Handler() {
            public Result handle(Map<String, Object> args) throws Exception {
                return text(apps.current().post(endpoint, params(args), actionTimeout(args)));
            }
        });
    }

    private String listApps() throws IOException {
        List<Apps.AppInfo> list = apps.list();
        List<Attacher.Jvm> others = apps.otherJvms();
        if (list.isEmpty() && others.isEmpty()) {
            return "No running Java applications found.";
        }
        StringBuilder sb = new StringBuilder();
        long connected = apps.currentPid();
        if (!list.isEmpty()) {
            sb.append("With swing2mcp agent:\n");
        }
        for (Apps.AppInfo app : list) {
            String windows = "";
            try {
                String ping = new AgentClient(app.pid, app.port, app.token).get("/ping", null, Duration.ofSeconds(3));
                for (String line : ping.split("\n")) {
                    if (line.startsWith("windows=")) {
                        windows = line.substring("windows=".length()).trim();
                        break;
                    }
                }
            } catch (Exception e) {
                windows = "(not responding: " + e.getMessage() + ")";
            }
            sb.append(app.pid == connected ? "* " : "  ").append("pid ").append(app.pid)
                    .append(" | Java ").append(app.java).append(" | ").append(app.command)
                    .append(" | windows: ").append(windows.length() == 0 ? "none" : windows).append('\n');
        }
        if (!others.isEmpty()) {
            sb.append(list.isEmpty() ? "" : "\n").append("Other Java processes (no agent yet; connect_app loads it):\n");
            for (Attacher.Jvm jvm : others) {
                sb.append("  pid ").append(jvm.pid).append(" | ")
                        .append(jvm.name == null || jvm.name.trim().length() == 0 ? "(unknown main class)" : jvm.name)
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static Result text(String text) {
        return new Result(text, null, false);
    }

    /** Tool arguments as agent form parameters; whole numbers may arrive as 5 or 5.0. */
    private static Map<String, Object> params(Map<String, Object> args) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number) {
                result.put(entry.getKey(), ((Number) value).longValue());
            } else if (value != null) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /** A JSON array argument becomes one form value. */
    private static void joined(Map<String, Object> args, Map<String, Object> form, String key, String separator) {
        if (args.get(key) instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object element : (List<?>) args.get(key)) {
                sb.append(sb.length() == 0 ? "" : separator).append(element);
            }
            form.put(key, sb.toString());
        }
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || value.toString().trim().length() == 0) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        return value.toString();
    }

    /** Room for the wait before the action plus the action itself. */
    private static Duration actionTimeout(Map<String, Object> args) {
        // typing sends several UI events per character; long texts need more time
        int typed = String.valueOf(args.containsKey("text") ? args.get("text") : "").length()
                + String.valueOf(args.containsKey("key") ? args.get("key") : "").length();
        Duration needed = Duration.ofMillis(num(args, "timeoutMs", 10000)).plusSeconds(20).plusMillis(100L * typed);
        return needed.compareTo(DEFAULT_TIMEOUT) > 0 ? needed : DEFAULT_TIMEOUT;
    }

    private static long num(Map<String, Object> args, String key, long def) {
        Object value = args.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString().trim());
    }
}
