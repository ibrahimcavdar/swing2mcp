package io.github.swing2mcp.agent;

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
 *   POST /type   ref, text, submit, append
 *   POST /select ref, value | index
 *   GET  /wait?text&gone&timeoutMs
 *   GET  /spring/beans?filter          Spring beans, if a Spring context can be found
 *   POST /spring/open  name            show a window/panel bean directly
 *   POST /reload classesDir, rebuild, refs   apply recompiled classes to the running application
 */
final class AgentServer {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    static final String TOKEN_HEADER = "X-Swing2mcp-Token";
    private static final long SNAPSHOT_TIMEOUT_MS = 5000;

    private final String token;
    private final RefRegistry refs = new RefRegistry();
    private final Actions actions = new Actions(refs);
    private final SpringSupport spring;
    private final HotReload hotReload;

    private AgentServer(String token, Instrumentation inst) {
        this.token = token;
        this.spring = new SpringSupport(inst);
        this.hotReload = new HotReload(inst, refs);
    }

    static AgentServer start(AgentOptions options, Instrumentation inst) throws Exception {
        final AgentServer agent = new AgentServer(newToken(), inst);
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
            if ("/ping".equals(path)) {
                text(exchange, 200, ping());
            } else if ("/snapshot".equals(path)) {
                text(exchange, 200, snapshot(params));
            } else if ("/screenshot".equals(path)) {
                send(exchange, 200, "image/png", screenshot(params));
            } else if ("/click".equals(path)) {
                text(exchange, 200, afterAction(actions.click(params)));
            } else if ("/type".equals(path)) {
                text(exchange, 200, afterAction(actions.type(params)));
            } else if ("/select".equals(path)) {
                text(exchange, 200, afterAction(actions.select(params)));
            } else if ("/spring/beans".equals(path)) {
                text(exchange, 200, spring.beans(params.get("filter")));
            } else if ("/spring/open".equals(path)) {
                text(exchange, 200, afterAction(spring.open(params.get("name"))));
            } else if ("/reload".equals(path)) {
                text(exchange, 200, afterAction(hotReload.reload(params)));
            } else if ("/wait".equals(path)) {
                text(exchange, 200, waitFor(params));
            } else {
                text(exchange, 404, "unknown endpoint " + path);
            }
        } catch (IllegalArgumentException e) {
            text(exchange, 400, e.getMessage());
        } catch (Edt.BusyException e) {
            text(exchange, 503, e.getMessage());
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
                    Component c = refs.resolve(windowRef.trim());
                    if (!(c instanceof Window)) {
                        throw new IllegalArgumentException(windowRef + " is not a window ref");
                    }
                    only = (Window) c;
                }
                return new SnapshotBuilder(refs, options).build(only);
            }
        }, SNAPSHOT_TIMEOUT_MS);
    }

    private byte[] screenshot(Map<String, String> params) throws Exception {
        final String ref = params.get("ref");
        BufferedImage image = Edt.call(new Callable<BufferedImage>() {
            public BufferedImage call() {
                Component target;
                if (Text.isBlank(ref)) {
                    target = Screenshots.defaultTarget();
                    if (target == null) {
                        throw new IllegalArgumentException("No visible window to capture");
                    }
                } else {
                    target = refs.resolve(ref.trim());
                    if (target == null) {
                        throw new IllegalArgumentException("Unknown or disposed ref " + ref + "; take a new snapshot");
                    }
                    if (!target.isShowing()) {
                        throw new IllegalArgumentException(ref + " is not visible");
                    }
                }
                return Screenshots.render(target);
            }
        }, SNAPSHOT_TIMEOUT_MS);
        return Screenshots.png(image);
    }

    private String afterAction(Actions.Result result) {
        if (!result.settled) {
            return result.message;
        }
        try {
            return result.message + "\n\n" + snapshot(new HashMap<String, String>());
        } catch (Exception e) {
            return result.message + "\n\n(snapshot failed: " + e.getMessage() + ")";
        }
    }

    /** Waits until text appears (or disappears with gone=true); without text, until any window is visible. */
    private String waitFor(Map<String, String> params) throws Exception {
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
                    return "Condition met.\n\n" + last;
                }
            } catch (Edt.BusyException busy) {
                // keep waiting; the application is still working
            }
            if (System.currentTimeMillis() >= deadline) {
                return "Timed out after " + timeout + " ms waiting for "
                        + (Text.isBlank(text) ? "a window" : Text.quote(text) + (gone ? " to disappear" : ""))
                        + ".\n\n" + (last == null ? "(UI thread busy, no snapshot)" : last);
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
