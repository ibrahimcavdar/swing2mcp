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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Installs the swing2mcp MCP server on this machine and registers it with the AI tools the developer
 * picks, so that they can connect to an application started with {@code mvn swing2mcp:run}.
 *
 * <pre>
 *   mvn swing2mcp:setup                                  asks which tools, shows the changes, then makes them
 *   mvn swing2mcp:setup -Dswing2mcp.clients=claude       no questions (also for -B and scripts)
 *   mvn swing2mcp:setup -Dswing2mcp.scope=project        settings files in this project instead of the user's
 *   mvn swing2mcp:setup -Dswing2mcp.mcpJavaHome=/path/to/jdk17
 * </pre>
 *
 * Nothing is written without being named first: either the developer picks the tools at the prompt and
 * confirms the listed changes, or names them with {@code swing2mcp.clients}.
 */
@Mojo(name = "setup", aggregator = true, threadSafe = true)
public class SetupMojo extends AbstractMojo {

    private static final int MCP_SERVER_MIN_JAVA = 17;
    private static final List<String> CLIENTS = Arrays.asList("claude", "cursor", "codex", "opencode");
    private static final List<String> CLIENT_NAMES = Arrays.asList("Claude Code", "Cursor", "Codex", "OpenCode");

    /** One change that setup is about to make: what it says in the plan, and how it is done. */
    private abstract static class Step {
        final String description;

        Step(String description) {
            this.description = description;
        }

        abstract void run() throws IOException, MojoExecutionException;
    }

    @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true, required = true)
    private Map<String, Artifact> pluginArtifacts;

    @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
    private String pluginVersion;

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File basedir;

    /** False with {@code mvn -B}: then nothing is asked, and tools must be named with swing2mcp.clients. */
    @Parameter(defaultValue = "${settings.interactiveMode}", readonly = true)
    private boolean interactive;

    /** AI tools to connect: claude, cursor, codex, opencode (comma separated). Without it, setup asks. */
    @Parameter(property = "swing2mcp.clients")
    private String clients;

    /**
     * Where the tools' settings are written: {@code user} (default; once per machine, nothing in the
     * project) or {@code project} (files in this project, which then hold paths of this machine).
     */
    @Parameter(property = "swing2mcp.scope", defaultValue = "user")
    private String scope;

    /** Java 17+ home that runs the MCP server. Default: Maven's JVM, then JAVA_HOME, if 17+. */
    @Parameter(property = "swing2mcp.mcpJavaHome")
    private File mcpJavaHome;

    /** Name under which the server appears in the AI tools. */
    @Parameter(property = "swing2mcp.serverName", defaultValue = "swing")
    private String serverName;

    /** The user's home directory; only changed by tests. */
    @Parameter(property = "swing2mcp.userHome", defaultValue = "${user.home}")
    private File userHome;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void execute() throws MojoExecutionException {
        final boolean projectScope = scope();
        final File java = Jvm.javaExecutable(mcpServerJavaHome());
        final File source = Jvm.pluginArtifact(pluginArtifacts, "swing2mcp");
        final File server = new File(userHome,
                ".swing2mcp" + File.separator + "bin" + File.separator + "swing2mcp-" + pluginVersion + ".jar");

        boolean named = clients != null && clients.trim().length() > 0;
        Set<String> selected;
        if (named) {
            selected = parse(clients);
        } else if (interactive) {
            selected = ask(projectScope);
        } else {
            selected = null;
        }

        List<Step> plan = new ArrayList<Step>();
        plan.add(new Step("copy the MCP server to " + server) {
            void run() throws MojoExecutionException {
                install(source, server);
            }
        });
        if (selected != null) {
            for (String client : selected) {
                plan.add(step(client, projectScope, java, server));
            }
        }

        getLog().info("swing2mcp setup will:");
        for (Step step : plan) {
            getLog().info("  - " + step.description);
        }
        getLog().info("Nothing else is changed" + (projectScope ? "." : ", and nothing is added to this project."));

        if (!named && interactive && !confirm()) {
            getLog().info("swing2mcp: nothing was changed.");
            return;
        }
        for (Step step : plan) {
            try {
                step.run();
            } catch (IOException e) {
                throw new MojoExecutionException("Could not " + step.description, e);
            }
        }
        if (selected == null) {
            getLog().info("swing2mcp: no AI tool was connected, because none was named and Maven runs without "
                    + "questions (-B). Name them, for example -Dswing2mcp.clients=claude (" + join(CLIENTS) + ").");
        }
        getLog().info("swing2mcp: any other MCP client can start the server with: "
                + quoteIfNeeded(java.getAbsolutePath()) + " -jar " + quoteIfNeeded(server.getAbsolutePath()));
        getLog().info("swing2mcp: to undo all of this: mvn swing2mcp:uninstall");
    }

    // ---------------------------------------------------------------- choosing

    /** True for the project scope. */
    private boolean scope() throws MojoExecutionException {
        String value = scope == null ? "user" : scope.trim().toLowerCase(Locale.ROOT);
        if (value.equals("project")) {
            return true;
        }
        if (value.equals("user")) {
            return false;
        }
        throw new MojoExecutionException("swing2mcp.scope must be user or project, not '" + scope + "'");
    }

    private Set<String> parse(String list) throws MojoExecutionException {
        Set<String> result = new LinkedHashSet<String>();
        for (String client : list.toLowerCase(Locale.ROOT).split("\\s*,\\s*")) {
            if (client.trim().length() == 0) {
                continue;
            }
            if (!CLIENTS.contains(client.trim())) {
                throw new MojoExecutionException("Unknown AI tool '" + client.trim() + "' in swing2mcp.clients; use "
                        + join(CLIENTS));
            }
            result.add(client.trim());
        }
        return result;
    }

    /** Asks which tools to connect. Enter takes the ones found on this machine. */
    private Set<String> ask(boolean projectScope) throws MojoExecutionException {
        List<String> found = new ArrayList<String>();
        StringBuilder menu = new StringBuilder();
        for (int i = 0; i < CLIENTS.size(); i++) {
            boolean here = installed(CLIENTS.get(i));
            if (here) {
                found.add(CLIENTS.get(i));
            }
            menu.append("  ").append(i + 1).append(") ").append(CLIENT_NAMES.get(i))
                    .append(here ? "   (found on this machine)" : "").append('\n');
        }
        System.out.println();
        System.out.println("swing2mcp: which AI tools should be able to use your Swing application?");
        System.out.print(menu);
        System.out.println("Their settings are changed " + (projectScope
                ? "in this project (-Dswing2mcp.scope=project)."
                : "for your user, once for all projects (for files in this project: -Dswing2mcp.scope=project)."));
        System.out.print("Numbers separated by commas" + (found.isEmpty() ? "" : ", Enter for the ones found")
                + ", 0 for none: ");
        System.out.flush();
        String answer = readLine();
        if (answer == null) {
            throw new MojoExecutionException("No answer could be read. Name the tools instead, for example "
                    + "-Dswing2mcp.clients=claude (" + join(CLIENTS) + ").");
        }
        answer = answer.trim();
        Set<String> selected = new LinkedHashSet<String>();
        if (answer.length() == 0) {
            selected.addAll(found);
            return selected;
        }
        for (String part : answer.split("[,\\s]+")) {
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new MojoExecutionException("'" + part + "' is not one of the numbers 0 to " + CLIENTS.size());
            }
            if (number < 0 || number > CLIENTS.size()) {
                throw new MojoExecutionException("'" + part + "' is not one of the numbers 0 to " + CLIENTS.size());
            }
            if (number > 0) {
                selected.add(CLIENTS.get(number - 1));
            }
        }
        return selected;
    }

    private boolean confirm() {
        System.out.print("Go ahead? [Y/n]: ");
        System.out.flush();
        String answer = readLine();
        if (answer == null) {
            return false;
        }
        answer = answer.trim().toLowerCase(Locale.ROOT);
        return answer.length() == 0 || answer.equals("y") || answer.equals("yes");
    }

    private BufferedReader stdin;

    private String readLine() {
        try {
            if (stdin == null) {
                stdin = new BufferedReader(new InputStreamReader(System.in));
            }
            return stdin.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether the tool seems to be used on this machine: its settings folder exists or its command is on PATH. */
    private boolean installed(String client) {
        if ("claude".equals(client)) {
            return new File(userHome, ".claude").isDirectory() || onPath("claude") != null;
        }
        if ("cursor".equals(client)) {
            return new File(userHome, ".cursor").isDirectory() || onPath("cursor") != null;
        }
        if ("codex".equals(client)) {
            return new File(userHome, ".codex").isDirectory() || onPath("codex") != null;
        }
        return new File(userHome, ".config/opencode").isDirectory() || onPath("opencode") != null;
    }

    static File onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        String[] extensions = Jvm.isWindows() ? new String[]{".exe", ".cmd", ".bat"} : new String[]{""};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.length() == 0) {
                continue;
            }
            for (String extension : extensions) {
                File candidate = new File(dir, command + extension);
                if (candidate.isFile()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the steps

    private Step step(String client, boolean projectScope, final File java, final File server) {
        if ("claude".equals(client)) {
            if (projectScope) {
                final File file = new File(basedir, ".mcp.json");
                return new Step("Claude Code: add \"" + serverName + "\" to " + file) {
                    void run() throws IOException {
                        writeMcpServers(file, java, server);
                    }
                };
            }
            final File claude = onPath("claude");
            final String byHand = "claude mcp add --scope user " + serverName + " -- "
                    + quoteIfNeeded(java.getAbsolutePath()) + " -jar " + quoteIfNeeded(server.getAbsolutePath());
            if (claude == null) {
                return new Step("Claude Code: the claude command is not on PATH, so run this yourself: " + byHand) {
                    void run() {
                        // nothing to do; the plan already says what to run
                    }
                };
            }
            return new Step("Claude Code: add \"" + serverName + "\" to your user settings by running: " + byHand) {
                void run() throws IOException {
                    claudeMcpAdd(claude, java, server, byHand);
                }
            };
        }
        if ("cursor".equals(client)) {
            final File file = new File(projectScope ? basedir : userHome, ".cursor/mcp.json");
            return new Step("Cursor: add \"" + serverName + "\" to " + file) {
                void run() throws IOException {
                    writeMcpServers(file, java, server);
                }
            };
        }
        if ("codex".equals(client)) {
            final File file = new File(projectScope ? basedir : userHome, ".codex/config.toml");
            return new Step("Codex: add [mcp_servers." + serverName + "] to " + file
                    + (projectScope ? " (Codex reads project settings only in trusted projects)" : "")) {
                void run() throws IOException {
                    writeCodex(file, java, server);
                }
            };
        }
        final File dir = projectScope ? basedir : new File(userHome, ".config/opencode");
        return new Step("OpenCode: add \"" + serverName + "\" to " + new File(dir, "opencode.json")) {
            void run() throws IOException {
                writeOpenCode(dir, java, server);
            }
        };
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
    private void install(File source, File target) throws MojoExecutionException {
        try {
            target.getParentFile().mkdirs();
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy the MCP server to " + target
                    + " (is an AI tool still running it?)", e);
        }
        getLog().info("swing2mcp: MCP server installed at " + target);
    }

    /**
     * Claude Code keeps user settings in a file it rewrites itself, so the entry is added through its own
     * command: an existing entry of the same name is removed first, then the new one is added.
     */
    private void claudeMcpAdd(File claude, File java, File server, String byHand) throws IOException {
        run(claude, "mcp", "remove", "--scope", "user", serverName);
        String[] added = run(claude, "mcp", "add", "--scope", "user", serverName, "--",
                java.getAbsolutePath(), "-jar", server.getAbsolutePath());
        if ("0".equals(added[0])) {
            getLog().info("swing2mcp: registered '" + serverName + "' with Claude Code (user settings)");
        } else {
            getLog().warn("swing2mcp: the claude command failed (exit " + added[0] + "): " + added[1].trim()
                    + "\nRun it yourself: " + byHand);
        }
    }

    /** Runs a command and returns its exit code and output. Batch files on Windows go through cmd.exe. */
    static String[] run(File executable, String... arguments) throws IOException {
        List<String> command = new ArrayList<String>();
        String name = executable.getName().toLowerCase(Locale.ROOT);
        if (Jvm.isWindows() && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
            // cmd.exe strips one pair of outer quotes: the whole line is wrapped in one more pair.
            StringBuilder line = new StringBuilder("\"").append(quoteIfNeeded(executable.getAbsolutePath()));
            for (String argument : arguments) {
                line.append(' ').append(quoteIfNeeded(argument));
            }
            command.add("cmd.exe");
            command.add("/s");
            command.add("/c");
            command.add(line.append('"').toString());
        } else {
            command.add(executable.getAbsolutePath());
            command.addAll(Arrays.asList(arguments));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[4096];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            output.write(buffer, 0, n);
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new String[]{"timeout", new String(output.toByteArray(), StandardCharsets.UTF_8)};
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        return new String[]{String.valueOf(process.exitValue()), new String(output.toByteArray(), StandardCharsets.UTF_8)};
    }

    static String quoteIfNeeded(String value) {
        return value.indexOf(' ') >= 0 ? "\"" + value + "\"" : value;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            sb.append(sb.length() == 0 ? "" : ", ").append(value);
        }
        return sb.toString();
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

    private void writeOpenCode(File dir, File java, File server) throws IOException {
        File jsonc = new File(dir, "opencode.jsonc");
        if (jsonc.isFile()) {
            // Rewriting would drop the user's comments.
            getLog().warn("swing2mcp: " + jsonc + " exists; add this under \"mcp\" yourself:\n"
                    + "\"" + serverName + "\": {\"type\": \"local\", \"command\": [\"" + java.getAbsolutePath()
                    + "\", \"-jar\", \"" + server.getAbsolutePath() + "\"], \"enabled\": true}");
            return;
        }
        File file = new File(dir, "opencode.json");
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
     * Codex reads TOML from config.toml (project settings only in trusted projects).
     * Our [mcp_servers.NAME] table and its sub-tables are replaced; everything else is kept as is.
     */
    private void writeCodex(File file, File java, File server) throws IOException {
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
        getLog().info("swing2mcp: registered '" + serverName + "' in " + file);
    }

    /** Literal string, so Windows backslashes need no escaping. */
    private static String tomlString(String value) {
        if (value.indexOf('\'') < 0) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static JsonObject read(File file) throws IOException {
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
