package io.github.ibrahimcavdar.swing2mcp.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** HTTP client for one agent, i.e. one running Swing application. */
final class AgentClient {

    static final String TOKEN_HEADER = "X-Swing2mcp-Token";

    static class AgentException extends IOException {
        AgentException(String message) {
            super(message);
        }
    }

    /** The application ended while a call was in progress (e.g. the call clicked "Exit"). */
    static final class AppExited extends AgentException {
        AppExited(long pid) {
            super("The application (pid " + pid + ") exited during this call.");
        }
    }

    static {
        // A click must never be sent twice: the JDK's client would repeat a POST whose connection broke.
        // Connections are not kept open either; an agent that exited would leave a dead one behind.
        System.setProperty("sun.net.http.retryPost", "false");
        System.setProperty("http.keepAlive", "false");
    }

    final long pid;
    final int port;
    private final String token;

    AgentClient(long pid, int port, String token) {
        this.pid = pid;
        this.port = port;
        this.token = token;
    }

    String get(String path, Map<String, ?> params, Duration timeout) throws IOException {
        return new String(send(path, params, false, timeout), StandardCharsets.UTF_8);
    }

    String post(String path, Map<String, ?> params, Duration timeout) throws IOException {
        return new String(send(path, params, true, timeout), StandardCharsets.UTF_8);
    }

    byte[] getBytes(String path, Map<String, ?> params, Duration timeout) throws IOException {
        return send(path, params, false, timeout);
    }

    private byte[] send(String path, Map<String, ?> params, boolean post, Duration timeout) throws IOException {
        String form = encode(params);
        URL url = new URL("http://127.0.0.1:" + port + path + (!post && form.length() > 0 ? "?" + form : ""));
        int status;
        byte[] body;
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
            connection.setRequestProperty(TOKEN_HEADER, token);
            if (post) {
                byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = connection.getOutputStream();
                out.write(bytes);
                out.close();
            }
            status = connection.getResponseCode();
            InputStream in = status == 200 ? connection.getInputStream() : connection.getErrorStream();
            body = in == null ? new byte[0] : readAll(in);
        } catch (IOException e) {
            if (exited()) {
                throw new AppExited(pid);
            }
            throw new AgentException("Cannot reach the application (pid " + pid + "): " + e.getMessage()
                    + ". It may have exited; use list_apps or launch_app.");
        }
        if (status != 200) {
            throw new AgentException(new String(body, StandardCharsets.UTF_8));
        }
        return body;
    }

    /** True if the process is gone or ends within a few seconds (shutdown hooks may still run). */
    private boolean exited() {
        return Processes.waitForExit(pid, port, 5000);
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String encode(Map<String, ?> params) {
        StringBuilder sb = new StringBuilder();
        if (params != null) {
            for (Map.Entry<String, ?> entry : params.entrySet()) {
                if (entry.getValue() != null) {
                    try {
                        sb.append(sb.length() == 0 ? "" : "&").append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                                .append('=').append(URLEncoder.encode(entry.getValue().toString(), "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return sb.toString();
    }
}
