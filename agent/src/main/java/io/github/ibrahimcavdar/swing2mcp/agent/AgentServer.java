package io.github.ibrahimcavdar.swing2mcp.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Tiny HTTP API bound to 127.0.0.1 and protected by a random token. The bridge (MCP
 * server) finds the port and token in a registration file named after the pid.
 *
 *   GET  /ping                         process info and window titles
 *   GET  /snapshot?window&maxRows&expandMenus
 *   GET  /screenshot?ref               PNG
 *   POST /click  ref, button, clickCount, row, col, x, y
 *   POST /type   ref, text, submit, append, mode (keys | set | os)
 *   POST /select ref, value | index
 *   POST /key    key, ref, mode       real key presses, e.g. Enter, Ctrl+S, "Tab Tab"
 *   POST /expand ref, path, collapse  expand or collapse a tree node
 *   POST /dialog ref, button, text, path   answer a dialog (message box, input, file chooser)
 *   POST /menu   path, ref, context   open a menu path ("File > Open") and click its last item
 *   POST /drag   ref, x, y, row, col, value, header, edge, toRef, toX, toY, toRow, toCol, toValue, at, dx, dy,
 *                steps, durationMs, button, modifiers, mode (auto | mouse | transfer | os), action
 *   (click, type, select, key, menu and drag also take dialog=<button>: answer the dialog the action opens)
 *   GET  /read   ref, start, limit, find, columns, path, depth   rows/items/nodes, paged
 *   GET  /wait?text&gone&timeoutMs
 *   GET  /spring/beans?filter          Spring beans, if a Spring context can be found
 *   POST /spring/open  name            show a window/panel bean directly
 *   POST /reload classesDir, rebuild, refs   apply recompiled classes to the running application
 *   GET  /locate?ref&timeoutMs         the ref and snapshot lines of what a locator finds
 *
 * Wherever a ref is taken, a locator such as {@code button "Save"} works too: see {@link Locators}.
 */
final class AgentServer {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    static final String TOKEN_HEADER = "X-Swing2mcp-Token";
    private static final long SNAPSHOT_TIMEOUT_MS = 5000;

    private static final java.util.Set<String> ACTIONS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "/click", "/type", "/select", "/key", "/expand", "/dialog", "/menu", "/drag", "/spring/open", "/reload"));

    private final String token;
    private final Object actionLock = new Object();
    private final RefRegistry refs = new RefRegistry();
    private final Locators locators = new Locators(refs);
    private final Actions actions = new Actions(refs);
    private final Drags drags = new Drags(refs);
    private final SpringSupport spring;
    private final HotReload hotReload;

    private AgentServer(String token, Instrumentation inst) {
        this.token = token;
        this.spring = new SpringSupport(inst);
        this.hotReload = new HotReload(inst, refs);
    }

    static AgentServer start(AgentOptions options, Instrumentation inst) throws Exception {
        final AgentServer agent = new AgentServer(newToken(), inst);
        ErrorMonitor.get().check();
        final HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), options.port), 0);
        http.setExecutor(daemonPool());
        http.createContext("/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                agent.handle(exchange);
            }
        });
        // The dispatcher thread inherits the daemon flag of the thread that starts the
        // server, so start it from a daemon thread: the agent must never keep the JVM alive.
        Thread starter = new Thread(new Runnable() {
            public void run() {
                http.start();
            }
        }, "swing2mcp-start");
        starter.setDaemon(true);
        starter.start();
        starter.join();
        register(options.registrationDir, http.getAddress().getPort(), agent.token);
        return agent;
    }

    // ---------------------------------------------------------------- HTTP plumbing

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String supplied = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
            if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(UTF8), token.getBytes(UTF8))) {
                send(exchange, 401, "text/plain", "invalid token".getBytes(UTF8));
                return;
            }
            Map<String, String> params = params(exchange);
            String path = exchange.getRequestURI().getPath();
            ErrorMonitor.get().check();
            // Actions run one at a time: their steps must not interleave on the UI thread
            // (parallel calls could otherwise type into the field another call just focused).
            Object lock = ACTIONS.contains(path) ? actionLock : new Object();
            synchronized (lock) {
                if ("/ping".equals(path)) {
                    text(exchange, 200, ping());
                } else if ("/snapshot".equals(path)) {
                    text(exchange, 200, snapshot(params) + ErrorMonitor.get().takeReport());
                } else if ("/locate".equals(path)) {
                    text(exchange, 200, locate(params));
                } else if ("/screenshot".equals(path)) {
                    send(exchange, 200, "image/png", screenshot(params));
                } else if ("/click".equals(path)) {
                    action(exchange, actions.answering(params, new Callable<Actions.Result>() {
                        public Actions.Result call() throws Exception {
                            return actions.click(params);
                        }
                    }));
                } else if ("/type".equals(path)) {
                    action(exchange, actions.answering(params, new Callable<Actions.Result>() {
                        public Actions.Result call() throws Exception {
                            return actions.type(params);
                        }
                    }));
                } else if ("/select".equals(path)) {
                    action(exchange, actions.answering(params, new Callable<Actions.Result>() {
                        public Actions.Result call() throws Exception {
                            return actions.select(params);
                        }
                    }));
                } else if ("/expand".equals(path)) {
                    action(exchange, actions.expand(params));
                } else if ("/read".equals(path)) {
                    text(exchange, 200, read(params) + ErrorMonitor.get().takeReport());
                } else if ("/key".equals(path)) {
                    action(exchange, actions.answering(params, new Callable<Actions.Result>() {
                        public Actions.Result call() throws Exception {
                            return actions.pressKey(params);
                        }
                    }));
                } else if ("/menu".equals(path)) {
                    action(exchange, actions.answering(params, new Callable<Actions.Result>() {
                        public Actions.Result call() throws Exception {
                            return actions.selectMenu(params);
                        }
                    }));
                } else if ("/drag".equals(path)) {
                    action(exchange, actions.answering(params, new Callable<Actions.Result>() {
                        public Actions.Result call() throws Exception {
                            return drags.drag(params);
                        }
                    }));
                } else if ("/dialog".equals(path)) {
                    action(exchange, actions.handleDialog(params));
                } else if ("/spring/beans".equals(path)) {
                    text(exchange, 200, spring.beans(params.get("filter")));
                } else if ("/spring/open".equals(path)) {
                    text(exchange, 200, afterAction(spring.open(params.get("name"))));
                } else if ("/reload".equals(path)) {
                    text(exchange, 200, afterAction(hotReload.reload(params)));
                } else if ("/wait".equals(path)) {
                    Waited waited = waitFor(params);
                    text(exchange, waited.timedOut ? 400 : 200, waited.text + ErrorMonitor.get().takeReport());
                } else {
                    text(exchange, 404, "unknown endpoint " + path);
                }
            }
        } catch (IllegalArgumentException e) {
            text(exchange, 400, e.getMessage() + ErrorMonitor.get().takeReport());
        } catch (Edt.BusyException e) {
            text(exchange, 503, e.getMessage() + ErrorMonitor.get().takeReport());
        } catch (Throwable t) {
            text(exchange, 500, t.toString());
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> params(HttpExchange exchange) throws IOException {
        Map<String, String> result = new HashMap<String, String>();
        parseForm(exchange.getRequestURI().getRawQuery(), result);
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            parseForm(new String(readAll(exchange.getRequestBody()), UTF8), result);
        }
        return result;
    }

    private static void parseForm(String form, Map<String, String> into) throws UnsupportedEncodingException {
        if (form == null || form.length() == 0) {
            return;
        }
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            into.put(key, value);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void text(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body.getBytes(UTF8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }

    // ---------------------------------------------------------------- endpoints

    private String ping() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("pid=").append(pid()).append('\n');
        sb.append("java=").append(System.getProperty("java.version")).append('\n');
        sb.append("command=").append(System.getProperty("sun.java.command", "")).append('\n');
        sb.append("hotReload=").append(hotReload.capability()).append('\n');
        String titles = Edt.call(new Callable<String>() {
            public String call() {
                StringBuilder t = new StringBuilder();
                for (Window window : SnapshotBuilder.showingWindows()) {
                    String title = window instanceof Frame ? ((Frame) window).getTitle()
                            : window instanceof Dialog ? ((Dialog) window).getTitle() : null;
                    if (title != null) {
                        t.append(t.length() > 0 ? ", " : "").append(Text.quote(title));
                    }
                }
                return t.toString();
            }
        }, 2000);
        sb.append("windows=").append(titles).append('\n');
        return sb.toString();
    }

    private String snapshot(Map<String, String> params) throws Exception {
        final SnapshotBuilder.Options options = new SnapshotBuilder.Options();
        if (params.get("maxRows") != null) {
            options.maxRows = Integer.parseInt(params.get("maxRows"));
        }
        options.expandMenus = "true".equalsIgnoreCase(params.get("expandMenus"));
        final String windowRef = params.get("window");
        return Edt.call(new Callable<String>() {
            public String call() {
                Window only = null;
                if (!Text.isBlank(windowRef)) {
                    Component c = locators.now(windowRef.trim());
                    if (!(c instanceof Window)) {
                        throw new IllegalArgumentException(windowRef + " is not a window ref");
                    }
                    only = (Window) c;
                }
                return new SnapshotBuilder(refs, options).build(only);
            }
        }, SNAPSHOT_TIMEOUT_MS);
    }

    private String read(final Map<String, String> params) throws Exception {
        final String ref = params.get("ref");
        if (Text.isBlank(ref)) {
            throw new IllegalArgumentException("Missing parameter: ref");
        }
        final Component c = locators.resolve(ref.trim(), params);
        return Edt.call(new Callable<String>() {
            public String call() {
                return Items.read(c, params);
            }
        }, SNAPSHOT_TIMEOUT_MS);
    }

    /**
     * What a locator (or ref) stands for right now: its ref, then its part of the snapshot. A locator that
     * matches nothing is waited for, up to timeoutMs.
     */
    private String locate(final Map<String, String> params) throws Exception {
        final String target = params.get("ref");
        if (Text.isBlank(target)) {
            throw new IllegalArgumentException("Missing parameter: ref");
        }
        if (Locators.isRef(target)) {
            throw new IllegalArgumentException("locate takes a locator such as button \"Save\", not a ref");
        }
        final String locator = target.trim();
        Locators.Found found = Actions.whenUsable(params, new StringBuilder(), new Callable<Locators.Found>() {
            public Locators.Found call() {
                return locators.find(locator);
            }
        });
        return "ref=" + refs.refFor(found.component) + "\n" + found.text;
    }

    private byte[] screenshot(Map<String, String> params) throws Exception {
        final String ref = params.get("ref");
        final Component given = Text.isBlank(ref) ? null : locators.resolve(ref.trim(), params);
        BufferedImage image = Edt.call(new Callable<BufferedImage>() {
            public BufferedImage call() {
                Component target = given;
                if (target == null) {
                    target = Screenshots.defaultTarget();
                    if (target == null) {
                        throw new IllegalArgumentException("No visible window to capture");
                    }
                } else {
                    if (!target.isShowing()) {
                        throw new IllegalArgumentException(ref + " is not visible");
                    }
                }
                return Screenshots.render(target);
            }
        }, SNAPSHOT_TIMEOUT_MS);
        return Screenshots.png(image);
    }

    /** Sends an action's result; one that could not be done is an error (400), with the snapshot. */
    private void action(HttpExchange exchange, Actions.Result result) throws IOException {
        text(exchange, result.failed ? 400 : 200, afterAction(result));
    }

    /** The action's message, the exceptions it caused in the application, and the new snapshot. */
    private String afterAction(Actions.Result result) {
        String message = result.message + ErrorMonitor.get().takeReport();
        if (!result.settled) {
            return message;
        }
        try {
            return message + "\n\n" + snapshot(new HashMap<String, String>());
        } catch (Exception e) {
            return message + "\n\n(snapshot failed: " + e.getMessage() + ")";
        }
    }

    /** What wait_for answers: the snapshot, and whether the condition was met in time. */
    private static final class Waited {
        final String text;
        final boolean timedOut;

        Waited(String text, boolean timedOut) {
            this.text = text;
            this.timedOut = timedOut;
        }
    }

    /**
     * Waits until text appears (or disappears with gone=true); without text, until any window is visible.
     * A timeout is reported as an error, with the last snapshot, so a caller cannot take it for success.
     */
    private Waited waitFor(Map<String, String> params) throws Exception {
        String text = params.get("text");
        boolean gone = "true".equalsIgnoreCase(params.get("gone"));
        long timeout = params.get("timeoutMs") == null ? 10000 : Long.parseLong(params.get("timeoutMs"));
        long deadline = System.currentTimeMillis() + timeout;
        String last = null;
        while (true) {
            try {
                last = snapshot(new HashMap<String, String>());
                boolean ready;
                if (Text.isBlank(text)) {
                    ready = !last.startsWith("(no visible windows)");
                } else {
                    ready = last.contains(text) != gone;
                }
                if (ready) {
                    return new Waited("Condition met.\n\n" + last, false);
                }
            } catch (Edt.BusyException busy) {
                // keep waiting; the application is still working
            }
            if (System.currentTimeMillis() >= deadline) {
                return new Waited("Timed out after " + timeout + " ms waiting for "
                        + (Text.isBlank(text) ? "a window" : Text.quote(text) + (gone ? " to disappear" : ""))
                        + ".\n\n" + (last == null ? "(UI thread busy, no snapshot)" : last), true);
            }
            Thread.sleep(250);
        }
    }

    // ---------------------------------------------------------------- registration

    static String pid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    private static void register(File dir, int port, String token) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        final File file = new File(dir, pid() + ".properties");
        File tmp = new File(dir, pid() + ".tmp");
        Properties props = new Properties();
        props.setProperty("pid", pid());
        props.setProperty("port", String.valueOf(port));
        props.setProperty("token", token);
        props.setProperty("java", System.getProperty("java.version"));
        props.setProperty("command", System.getProperty("sun.java.command", ""));
        props.setProperty("started", String.valueOf(System.currentTimeMillis()));
        tmp.delete();
        if (tmp.createNewFile()) {
            // owner-only on POSIX; on Windows the user profile is already private
            tmp.setReadable(false, false);
            tmp.setReadable(true, true);
            tmp.setWritable(false, false);
            tmp.setWritable(true, true);
        }
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            props.store(out, "swing2mcp agent");
        } finally {
            out.close();
        }
        file.delete();
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot write " + file);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                file.delete();
            }
        }, "swing2mcp-unregister"));
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static ExecutorService daemonPool() {
        return Executors.newCachedThreadPool(new ThreadFactory() {
            private int count;

            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "swing2mcp-http-" + (++count));
                t.setDaemon(true);
                return t;
            }
        });
    }
}
