package io.github.ibrahimcavdar.swing2mcp.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Knows which Swing applications are running with the agent, launches new ones and keeps
 * track of the one that is being talked to.
 *
 * Every agent writes {@code ~/.swing2mcp/jvms/<pid>.properties} with its port and token.
 *
 * <p>Internal, like the rest of this package: shared by the test API and the MCP server, and free to
 * change between releases.
 */
public final class Apps {

    public static final class AppInfo {
        public final long pid;
        public final int port;
        public final String token;
        public final String java;
        public final String command;

        AppInfo(long pid, int port, String token, String java, String command) {
            this.pid = pid;
            this.port = port;
            this.token = token;
            this.java = java;
            this.command = command;
        }
    }

    private static final String AGENT_RESOURCE = "/swing2mcp/swing2mcp-agent.jar";

    private final Path home;
    private final Path registrations;
    private final Path logs;
    private AgentClient current;

    public Apps(Path home) {
        this.home = home;
        this.registrations = home.resolve("jvms");
        this.logs = home.resolve("logs");
    }

    /** The usual place: {@code ~/.swing2mcp}, where every agent of this user registers. */
    public static Apps ofUser() {
        return new Apps(Paths.get(System.getProperty("user.home"), ".swing2mcp"));
    }

    /** Running applications; registrations of dead processes are removed. */
    public synchronized List<AppInfo> list() throws IOException {
        List<AppInfo> result = new ArrayList<AppInfo>();
        if (!Files.isDirectory(registrations)) {
            return result;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(registrations, "*.properties")) {
            for (Path file : files) {
                Properties props = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
                    props.load(reader);
                } catch (IOException e) {
                    continue;
                }
                long pid;
                int port;
                try {
                    pid = Long.parseLong(props.getProperty("pid", "-1"));
                    port = Integer.parseInt(props.getProperty("port", "-1"));
                } catch (NumberFormatException e) {
                    continue; // still being written
                }
                if (!Processes.isAlive(pid, port)) {
                    Files.deleteIfExists(file);
                    continue;
                }
                result.add(new AppInfo(pid, port, props.getProperty("token"),
                        props.getProperty("java", "?"), props.getProperty("command", "")));
            }
        }
        return result;
    }

    public synchronized AgentClient connect(long pid) throws IOException {
        AppInfo app = find(pid);
        if (app == null) {
            throw new IOException("No swing2mcp-enabled application with pid "
                    + pid + ". Use list_apps to see running applications.");
        }
        current = new AgentClient(app.pid, app.port, app.token);
        return current;
    }

    /** The running application with this pid that has the agent, or null. */
    public AppInfo find(long pid) throws IOException {
        for (AppInfo app : list()) {
            if (app.pid == pid) {
                return app;
            }
        }
        return null;
    }

    /**
     * The connected application; connects automatically when exactly one application has the
     * agent. Never attaches on its own: loading the agent always needs an explicit request.
     */
    public synchronized AgentClient current() throws IOException {
        if (current != null && Processes.isAlive(current.pid, current.port)) {
            return current;
        }
        current = null;
        List<AppInfo> apps = list();
        if (apps.isEmpty()) {
            throw new IOException("No application is connected. Use list_apps, then connect_app with a pid, "
                    + "or start one with launch_app.");
        }
        if (apps.size() > 1) {
            StringBuilder pids = new StringBuilder();
            for (AppInfo app : apps) {
                pids.append(pids.length() == 0 ? "" : ", ").append(app.pid);
            }
            throw new IOException("Several applications are running (pids " + pids + "). Choose one with connect_app.");
        }
        return connect(apps.get(0).pid);
    }

    /** The pid of the connected application, or -1. */
    public synchronized long currentPid() {
        return current == null ? -1 : current.pid;
    }

    /**
     * Runs a shell command with the agent injected through JAVA_TOOL_OPTIONS, so it works
     * for {@code java -jar}, start scripts and IDE-generated command lines alike.
     */
    public String launch(String command, Path cwd, Duration timeout) throws IOException, InterruptedException {
        return launch(Processes.isWindows() ? Arrays.asList("cmd.exe", "/c", command)
                : Arrays.asList("/bin/sh", "-c", command), cwd, timeout);
    }

    /** As {@link #launch(String, Path, Duration)}, for a program and its arguments, without a shell. */
    public String launch(List<String> program, Path cwd, Duration timeout) throws IOException, InterruptedException {
        Path agentJar = agentJar();
        Files.createDirectories(logs);
        Path log = logs.resolve("app-" + System.currentTimeMillis() + ".log");

        ProcessBuilder builder = new ProcessBuilder(program);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        Map<String, String> env = builder.environment();
        String existing = env.containsKey("JAVA_TOOL_OPTIONS") ? env.get("JAVA_TOOL_OPTIONS") : "";
        env.put("JAVA_TOOL_OPTIONS", (existing + " " + agentOption(agentJar, registrations)).trim());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(new File(Processes.isWindows() ? "NUL" : "/dev/null")));

        Set<Long> before = new HashSet<Long>();
        for (AppInfo app : list()) {
            before.add(app.pid);
        }
        Process process = builder.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            // On Java 8 the pids of the started processes are unknown: any new application is taken.
            Set<Long> family = Processes.family(process);
            AppInfo fallback = null;
            for (AppInfo app : list()) {
                if (family != null && family.contains(app.pid)) {
                    connect(app.pid);
                    return launched(app, log);
                }
                if (!before.contains(app.pid)) {
                    fallback = app;
                }
            }
            if (fallback != null && (family == null || !process.isAlive())) {
                // e.g. the command handed the work to a detached JVM
                connect(fallback.pid);
                return launched(fallback, log);
            }
            if (!process.isAlive() && fallback == null) {
                return failure("The command exited with code " + process.exitValue()
                        + " before the agent started.", log);
            }
            Thread.sleep(100);
        }
        return failure("The agent did not report within " + timeout.getSeconds() + " s.", log);
    }

    /**
     * The -javaagent option for JAVA_TOOL_OPTIONS. It tells the agent where to register: an application
     * that is started with another user.home (to keep its settings apart) would otherwise register in a
     * folder that nobody looks into.
     */
    static String agentOption(Path agentJar, Path registrations) {
        String option = "-javaagent:" + agentJar;
        String dir = registrations.toAbsolutePath().toString();
        if (dir.indexOf(',') < 0 && dir.indexOf('"') < 0) { // a comma separates the agent's options
            option += "=dir=" + dir;
        }
        // The JVM splits JAVA_TOOL_OPTIONS on spaces but honours quotes (also on Java 8), which
        // matters for Windows user folders such as C:\Users\Ali Veli.
        return option.indexOf(' ') >= 0 ? "\"" + option + "\"" : option;
    }

    private static String launched(AppInfo app, Path log) {
        return "Launched pid " + app.pid + " (Java " + app.java + "), agent connected.\nLog: " + log;
    }

    /**
     * Ends an application that has the agent. Any other pid is refused: a wrong number from the
     * AI tool must not end an unrelated program.
     */
    public String stop(long pid) throws IOException {
        if (find(pid) == null) {
            throw new IOException("Not stopped: pid " + pid + " is not an application with the swing2mcp agent. "
                    + "stop_app only stops the applications that list_apps shows with the agent; "
                    + "to stop another Java application, connect to it with connect_app first.");
        }
        Processes.destroyTree(pid);
        synchronized (this) {
            if (current != null && current.pid == pid) {
                current = null;
            }
        }
        Files.deleteIfExists(registrations.resolve(pid + ".properties"));
        return "Stopped pid " + pid + ".";
    }

    private static String failure(String message, Path log) throws IOException {
        List<String> lines = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8)
                : Collections.<String>emptyList();
        List<String> tail = lines.subList(Math.max(0, lines.size() - 30), lines.size());
        StringBuilder text = new StringBuilder(message).append(" Log: ").append(log);
        for (String line : tail) {
            text.append('\n').append(line);
        }
        throw new IOException(text.toString());
    }

    /** The agent jar as a file: the embedded one, copied to ~/.swing2mcp/agent/ and named by content hash. */
    public Path agentJar() throws IOException {
        String override = System.getProperty("swing2mcp.agentJar");
        if (override != null) {
            return Paths.get(override).toAbsolutePath();
        }
        InputStream in = Apps.class.getResourceAsStream(AGENT_RESOURCE);
        if (in == null) {
            throw new IOException("Agent jar not embedded; build with mvn package or set -Dswing2mcp.agentJar");
        }
        byte[] bytes = AgentClient.readAll(in);
        StringBuilder hash = new StringBuilder();
        try {
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hash.append(String.format("%02x", b & 0xff));
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        Path dir = home.resolve("agent");
        Path jar = dir.resolve("swing2mcp-agent-" + hash.substring(0, 12) + ".jar");
        if (!Files.exists(jar)) {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "agent", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        return jar;
    }
}
