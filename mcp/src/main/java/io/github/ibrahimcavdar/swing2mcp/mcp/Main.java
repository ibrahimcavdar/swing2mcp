package io.github.ibrahimcavdar.swing2mcp.mcp;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * swing2mcp MCP server: speaks the Model Context Protocol over stdin and stdout, one JSON-RPC message
 * per line. An AI tool starts it with
 *
 *   java -jar swing2mcp.jar
 *
 * It implements the part of the protocol that a tools-only server needs (initialize, ping, tools/list,
 * tools/call) itself, without a library, so that it runs on Java 8 and brings no dependencies.
 * stdout carries the protocol, so nothing else may be printed there.
 */
public final class Main {

    static final String VERSION = version();

    /** Protocol revisions this server speaks, newest first. They do not differ for what it implements. */
    private static final List<String> PROTOCOLS = Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05");

    private static final String INSTRUCTIONS =
            "swing2mcp lets you see and operate Java Swing desktop applications.\n"
            + "Typical flow: launch_app (or list_apps/connect_app for one already running) -> wait_for -> "
            + "snapshot -> click / type_text / select_option using [ref=eN] values from the snapshot. "
            + "Actions return the updated snapshot. A modal dialog blocks other windows until it is closed. "
            + "Use screenshot to check visual layout or custom-painted components.";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    private final Tools tools;
    private final OutputStream out;

    private Main(Tools tools, OutputStream out) {
        this.tools = tools;
        this.out = out;
    }

    /** The build's version (filtered resource); "dev" if the resource is missing, e.g. in an unfiltered IDE build. */
    private static String version() {
        try (InputStream in = Main.class.getResourceAsStream("/swing2mcp/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version", "").trim();
                if (v.length() > 0 && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "dev";
    }

    public static void main(String[] args) throws Exception {
        // The protocol gets the real stdout; anything else that prints lands on stderr.
        OutputStream protocol = new FileOutputStream(FileDescriptor.out);
        System.setOut(System.err);
        Tools tools = new Tools(new Apps(Paths.get(System.getProperty("user.home"), ".swing2mcp")));
        new Main(tools, protocol).serve(new FileInputStream(FileDescriptor.in));
        // The client closing stdin means it is done with us.
        System.exit(0);
    }

    private void serve(InputStream stdin) throws IOException {
        // Tool calls can take long (wait_for) and may come in parallel; the other requests are quick.
        ExecutorService calls = Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "swing2mcp-tool-call");
                thread.setDaemon(true);
                return thread;
            }
        });
        BufferedReader in = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            if (line.trim().length() == 0) {
                continue;
            }
            Object message;
            try {
                message = Json.parse(line);
            } catch (IllegalArgumentException e) {
                send(error(null, PARSE_ERROR, e.getMessage()));
                continue;
            }
            // Older protocol revisions allow several messages in one array.
            for (Object one : message instanceof List ? (List<?>) message : Arrays.asList(message)) {
                receive(one, calls);
            }
        }
        // stdin ended: let the calls that are still running answer (a script may pipe its requests in).
        calls.shutdown();
        try {
            calls.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void receive(Object message, ExecutorService calls) {
        if (!(message instanceof Map)) {
            send(error(null, INVALID_REQUEST, "A message must be a JSON object"));
            return;
        }
        final Map<?, ?> request = (Map<?, ?>) message;
        final Object id = request.get("id");
        if (!(request.get("method") instanceof String)) {
            return; // an answer to something; this server never asks
        }
        final String method = (String) request.get("method");
        if (!request.containsKey("id")) {
            return; // a notification (initialized, cancelled...): nothing to answer
        }
        final Map<?, ?> params = request.get("params") instanceof Map ? (Map<?, ?>) request.get("params")
                : new LinkedHashMap<String, Object>();
        if (method.equals("initialize")) {
            send(result(id, initialize(params)));
        } else if (method.equals("ping")) {
            send(result(id, new LinkedHashMap<String, Object>()));
        } else if (method.equals("tools/list")) {
            Map<String, Object> list = new LinkedHashMap<String, Object>();
            list.put("tools", tools.definitions());
            send(result(id, list));
        } else if (method.equals("tools/call")) {
            if (!(params.get("name") instanceof String) || !tools.has((String) params.get("name"))) {
                send(error(id, INVALID_PARAMS, "Unknown tool: " + params.get("name")));
                return;
            }
            calls.execute(new Runnable() {
                public void run() {
                    Map<String, Object> arguments = new LinkedHashMap<String, Object>();
                    if (params.get("arguments") instanceof Map) {
                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) params.get("arguments")).entrySet()) {
                            arguments.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    Map<String, Object> answer;
                    try {
                        answer = result(id, tools.call((String) params.get("name"), arguments));
                    } catch (Throwable t) {
                        t.printStackTrace();
                        answer = error(id, -32603, String.valueOf(t));
                    }
                    send(answer);
                }
            });
        } else {
            send(error(id, METHOD_NOT_FOUND, "Method not found: " + method));
        }
    }

    private static Map<String, Object> initialize(Map<?, ?> params) {
        Object requested = params.get("protocolVersion");
        Map<String, Object> toolsCapability = new LinkedHashMap<String, Object>();
        toolsCapability.put("listChanged", false);
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("tools", toolsCapability);
        Map<String, Object> serverInfo = new LinkedHashMap<String, Object>();
        serverInfo.put("name", "swing2mcp");
        serverInfo.put("version", VERSION);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        // The client's revision if this server speaks it, otherwise the newest one it does.
        result.put("protocolVersion", PROTOCOLS.contains(requested) ? requested : PROTOCOLS.get(0));
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("result", result);
        return message;
    }

    private static Map<String, Object> error(Object id, int code, String text) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", text);
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("error", error);
        return message;
    }

    /** One message per line; tool calls answer from their own threads. */
    private synchronized void send(Map<String, Object> message) {
        try {
            out.write((Json.write(message) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // the client is gone; stdin will end too
        }
    }
}
