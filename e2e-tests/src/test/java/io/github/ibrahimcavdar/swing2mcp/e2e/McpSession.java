package io.github.ibrahimcavdar.swing2mcp.e2e;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal MCP client: starts the server jar and talks JSON-RPC over its stdin and stdout, as an AI
 * tool does. Written by hand so the tests do not share code with the server they test.
 */
final class McpSession implements AutoCloseable {

    /** What a tool returned: its text, whether it was an error, and any images. */
    record Result(String text, boolean error, List<byte[]> images) {

        /** The ref of the first snapshot line that contains {@code marker}, e.g. {@code field=txtName}. */
        String ref(String marker) {
            for (String line : text.split("\n")) {
                if (line.contains(marker)) {
                    Matcher m = REF.matcher(line);
                    if (m.find()) {
                        return m.group(1);
                    }
                }
            }
            throw new AssertionError("No line with a ref contains '" + marker + "' in:\n" + text);
        }
    }

    private static final Pattern REF = Pattern.compile("\\[ref=(e\\d+)]");
    private static final String END = "<end of output>";

    private final Gson gson = new Gson();
    private final Process process;
    private final BufferedWriter toServer;
    private final BlockingQueue<String> fromServer = new LinkedBlockingQueue<>();
    private int nextId;

    McpSession(Path serverJar, Path errorLog) throws IOException {
        process = new ProcessBuilder(TestEnvironment.java(), "-jar", serverJar.toString())
                .redirectError(errorLog.toFile())
                .start();
        toServer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    fromServer.add(line);
                }
            } catch (IOException ignored) {
                // reported as the end of the output
            }
            fromServer.add(END);
        }, "mcp-server-output");
        reader.setDaemon(true);
        reader.start();

        JsonObject client = new JsonObject();
        client.addProperty("name", "swing2mcp-e2e-tests");
        client.addProperty("version", "0");
        JsonObject init = new JsonObject();
        init.addProperty("protocolVersion", "2025-06-18");
        init.add("capabilities", new JsonObject());
        init.add("clientInfo", client);
        request("initialize", init, 30);
        send("notifications/initialized", null, false);
    }

    /** Names of the tools the server offers. */
    List<String> toolNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (JsonElement tool : request("tools/list", new JsonObject(), 30).getAsJsonArray("tools")) {
            names.add(tool.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    /** Calls a tool; {@code args} are name/value pairs. */
    Result call(String tool, Object... args) throws IOException {
        JsonObject arguments = new JsonObject();
        for (int i = 0; i < args.length; i += 2) {
            arguments.add((String) args[i], gson.toJsonTree(args[i + 1]));
        }
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", arguments);
        JsonObject result = request("tools/call", params, 120);

        StringBuilder text = new StringBuilder();
        List<byte[]> images = new ArrayList<>();
        JsonArray content = result.getAsJsonArray("content");
        for (JsonElement element : content == null ? new JsonArray() : content) {
            JsonObject item = element.getAsJsonObject();
            String type = item.get("type").getAsString();
            if (type.equals("text")) {
                text.append(text.length() == 0 ? "" : "\n").append(item.get("text").getAsString());
            } else if (type.equals("image")) {
                images.add(Base64.getDecoder().decode(item.get("data").getAsString()));
            }
        }
        boolean error = result.has("isError") && result.get("isError").getAsBoolean();
        return new Result(text.toString(), error, images);
    }

    private JsonObject request(String method, JsonObject params, int timeoutSeconds) throws IOException {
        JsonObject reply = reply(method, params, timeoutSeconds);
        if (reply.has("error")) {
            throw new IOException(method + " failed: " + reply.get("error"));
        }
        return reply.getAsJsonObject("result");
    }

    /** Sends a request and returns the whole answer, whether it is a result or an error. */
    JsonObject reply(String method, JsonObject params, int timeoutSeconds) throws IOException {
        int id = send(method, params, true);
        return await(method, id, timeoutSeconds);
    }

    /** Sends a line as it is and returns the answer that has no id, which is how a server answers nonsense. */
    JsonObject replyToLine(String line) throws IOException {
        toServer.write(line);
        toServer.write("\n");
        toServer.flush();
        return await("a raw line", null, 30);
    }

    private JsonObject await(String method, Integer id, int timeoutSeconds) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            String line;
            try {
                line = fromServer.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for " + method);
            }
            if (line == null) {
                throw new IOException("No answer to " + method + " within " + timeoutSeconds + " s");
            }
            if (line.equals(END)) {
                throw new IOException("The MCP server stopped before answering " + method);
            }
            JsonObject reply = gson.fromJson(line, JsonObject.class);
            boolean noId = !reply.has("id") || reply.get("id").isJsonNull();
            if (id == null ? noId : !noId && reply.get("id").getAsInt() == id) {
                return reply;
            }
        }
    }

    private int send(String method, JsonObject params, boolean expectsReply) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        int id = expectsReply ? ++nextId : 0;
        if (expectsReply) {
            message.addProperty("id", id);
        }
        toServer.write(gson.toJson(message));
        toServer.write("\n");
        toServer.flush();
        return id;
    }

    /** Closing stdin tells the server that the client is done. */
    @Override
    public void close() throws Exception {
        toServer.close();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    /** Fails with the tool's whole output, which is what explains a failure. */
    static Result ok(Result result) {
        if (result.error()) {
            throw new AssertionError("The tool reported an error:\n" + result.text());
        }
        return result;
    }
}
