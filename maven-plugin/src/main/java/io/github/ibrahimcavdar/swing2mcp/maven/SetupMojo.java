package io.github.ibrahimcavdar.swing2mcp.maven;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Installs the swing2mcp MCP server on this machine and registers it with AI tools in this
 * project, so that they can connect to an application started with {@code mvn swing2mcp:run}.
 *
 * <pre>
 *   mvn swing2mcp:setup
 *   mvn swing2mcp:setup -Dswing2mcp.clients=cursor
 *   mvn swing2mcp:setup -Dswing2mcp.mcpJavaHome=/path/to/jdk17
 * </pre>
 *
 * The written files contain absolute paths of this machine; each developer runs setup once.
 */
@Mojo(name = "setup", aggregator = true, threadSafe = true)
public class SetupMojo extends AbstractMojo {

    private static final int MCP_SERVER_MIN_JAVA = 17;

    @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true, required = true)
    private Map<String, Artifact> pluginArtifacts;

    @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
    private String pluginVersion;

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File basedir;

    /** AI tools to configure: claude, cursor, opencode, codex (comma separated). */
    @Parameter(property = "swing2mcp.clients", defaultValue = "claude,cursor,opencode,codex")
    private String clients;

    /** Java 17+ home that runs the MCP server. Default: Maven's JVM, then JAVA_HOME, if 17+. */
    @Parameter(property = "swing2mcp.mcpJavaHome")
    private File mcpJavaHome;

    /** Name under which the server appears in the AI tools. */
    @Parameter(property = "swing2mcp.serverName", defaultValue = "swing")
    private String serverName;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void execute() throws MojoExecutionException {
        File java = Jvm.javaExecutable(mcpServerJavaHome());
        File server = install();
        getLog().info("swing2mcp: MCP server installed at " + server);

        Set<String> selected = new LinkedHashSet<String>(Arrays.asList(clients.toLowerCase().split("\\s*,\\s*")));
        for (String client : selected) {
            try {
                if ("claude".equals(client)) {
                    writeMcpServers(new File(basedir, ".mcp.json"), java, server);
                } else if ("cursor".equals(client)) {
                    writeMcpServers(new File(basedir, ".cursor/mcp.json"), java, server);
                } else if ("opencode".equals(client)) {
                    writeOpenCode(java, server);
                } else if ("codex".equals(client)) {
                    writeCodex(java, server);
                } else if (client.length() > 0) {
                    getLog().warn("swing2mcp: unknown client '" + client + "' (use claude, cursor, opencode, codex)");
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot write configuration for " + client, e);
            }
        }
        getLog().info("swing2mcp: other MCP clients can start the server with: "
                + java + " -jar " + server);
        getLog().info("swing2mcp: these files contain paths of this machine; each developer runs setup once.");
    }

    private File mcpServerJavaHome() throws MojoExecutionException {
        if (mcpJavaHome != null) {
            int version = Jvm.majorVersion(mcpJavaHome);
            if (version >= 0 && version < MCP_SERVER_MIN_JAVA) {
                throw new MojoExecutionException(mcpJavaHome + " is Java " + version + "; the MCP server needs Java "
                        + MCP_SERVER_MIN_JAVA + "+");
            }
            return mcpJavaHome;
        }
        File mavenJava = new File(System.getProperty("java.home"));
        if (Jvm.parseMajor(System.getProperty("java.specification.version", "")) >= MCP_SERVER_MIN_JAVA) {
            return mavenJava;
        }
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && Jvm.majorVersion(new File(javaHomeEnv)) >= MCP_SERVER_MIN_JAVA) {
            return new File(javaHomeEnv);
        }
        throw new MojoExecutionException("The MCP server needs Java " + MCP_SERVER_MIN_JAVA + "+, but Maven runs on "
                + "Java " + System.getProperty("java.specification.version") + ". Point to a newer JDK with "
                + "-Dswing2mcp.mcpJavaHome=/path/to/jdk17 (the application itself can still run on Java 8).");
    }

    /** Copies the server jar to ~/.swing2mcp/bin, versioned so a running server never blocks an update. */
    private File install() throws MojoExecutionException {
        File source = Jvm.pluginArtifact(pluginArtifacts, "swing2mcp");
        File target = new File(System.getProperty("user.home"),
                ".swing2mcp" + File.separator + "bin" + File.separator + "swing2mcp-" + pluginVersion + ".jar");
        try {
            target.getParentFile().mkdirs();
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy the MCP server to " + target
                    + " (is an AI tool still running it?)", e);
        }
        return target;
    }

    /** Claude Code (.mcp.json) and Cursor (.cursor/mcp.json) share the "mcpServers" layout. */
    private void writeMcpServers(File file, File java, File server) throws IOException {
        JsonObject root = read(file);
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "stdio");
        entry.addProperty("command", java.getAbsolutePath());
        JsonArray args = new JsonArray();
        args.add("-jar");
        args.add(server.getAbsolutePath());
        entry.add("args", args);
        child(root, "mcpServers").add(serverName, entry);
        write(file, root);
    }

    private void writeOpenCode(File java, File server) throws IOException {
        File jsonc = new File(basedir, "opencode.jsonc");
        if (jsonc.isFile()) {
            // Rewriting would drop the user's comments.
            getLog().warn("swing2mcp: " + jsonc.getName() + " exists; add this under \"mcp\" yourself:\n"
                    + "\"" + serverName + "\": {\"type\": \"local\", \"command\": [\"" + java.getAbsolutePath()
                    + "\", \"-jar\", \"" + server.getAbsolutePath() + "\"], \"enabled\": true}");
            return;
        }
        File file = new File(basedir, "opencode.json");
        JsonObject root = read(file);
        if (!root.has("$schema")) {
            root.addProperty("$schema", "https://opencode.ai/config.json");
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "local");
        JsonArray command = new JsonArray();
        command.add(java.getAbsolutePath());
        command.add("-jar");
        command.add(server.getAbsolutePath());
        entry.add("command", command);
        entry.addProperty("enabled", true);
        child(root, "mcp").add(serverName, entry);
        write(file, root);
    }

    /**
     * Codex reads TOML from .codex/config.toml (project config is only used for trusted projects).
     * Our [mcp_servers.NAME] table and its sub-tables are replaced; everything else is kept as is.
     */
    private void writeCodex(File java, File server) throws IOException {
        File file = new File(basedir, ".codex/config.toml");
        String header = "[mcp_servers." + serverName + "]";
        String subPrefix = "[mcp_servers." + serverName + ".";
        StringBuilder kept = new StringBuilder();
        if (file.isFile()) {
            boolean skipping = false;
            for (String line : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\r?\n", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    skipping = trimmed.equals(header) || trimmed.startsWith(subPrefix);
                }
                if (!skipping) {
                    kept.append(line).append('\n');
                }
            }
        }
        String existing = kept.toString().replaceAll("\\s+$", "");
        StringBuilder toml = new StringBuilder(existing);
        if (toml.length() > 0) {
            toml.append("\n\n");
        }
        toml.append(header).append('\n')
                .append("command = ").append(tomlString(java.getAbsolutePath())).append('\n')
                .append("args = [\"-jar\", ").append(tomlString(server.getAbsolutePath())).append("]\n")
                .append("startup_timeout_sec = 30\n");
        file.getAbsoluteFile().getParentFile().mkdirs();
        Files.write(file.toPath(), toml.toString().getBytes(StandardCharsets.UTF_8));
        getLog().info("swing2mcp: registered '" + serverName + "' in " + file
                + " (Codex uses project config only when the project is trusted)");
    }

    /** Literal string, so Windows backslashes need no escaping. */
    private static String tomlString(String value) {
        if (value.indexOf('\'') < 0) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static JsonObject read(File file) throws IOException {
        if (!file.isFile()) {
            return new JsonObject();
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (text.trim().length() == 0) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) {
            throw new IOException(file + " is not a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static JsonObject child(JsonObject parent, String name) {
        JsonElement existing = parent.get(name);
        if (existing != null && existing.isJsonObject()) {
            return existing.getAsJsonObject();
        }
        JsonObject created = new JsonObject();
        parent.add(name, created);
        return created;
    }

    private void write(File file, JsonObject root) throws IOException {
        file.getAbsoluteFile().getParentFile().mkdirs();
        Files.write(file.toPath(), (gson.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8));
        getLog().info("swing2mcp: registered '" + serverName + "' in " + file);
    }
}
