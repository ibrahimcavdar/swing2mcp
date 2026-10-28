package io.github.swing2mcp.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/** HTTP client for one agent, i.e. one running Swing application. */
final class AgentClient {

    static final String TOKEN_HEADER = "X-Swing2mcp-Token";

    static final class AgentException extends IOException {
        AgentException(String message) {
            super(message);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    final long pid;
    private final int port;
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
        String uri = "http://127.0.0.1:" + port + path + (!post && !form.isEmpty() ? "?" + form : "");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(timeout)
                .header(TOKEN_HEADER, token);
        if (post) {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
        } else {
            request.GET();
        }
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (IOException e) {
            throw new AgentException("Cannot reach the application (pid " + pid + "): " + e.getMessage()
                    + ". It may have exited; use list_apps or launch_app.");
        }
        if (response.statusCode() != 200) {
            throw new AgentException(new String(response.body(), StandardCharsets.UTF_8));
        }
        return response.body();
    }

    private static String encode(Map<String, ?> params) {
        StringJoiner joiner = new StringJoiner("&");
        if (params != null) {
            params.forEach((key, value) -> {
                if (value != null) {
                    joiner.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
                }
            });
        }
        return joiner.toString();
    }
}
