package io.github.ibrahimcavdar.swing2mcp.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Knows which Swing applications are running with the agent, launches new ones and keeps
 * track of the one the tools currently talk to.
 *
 * Every agent writes {@code ~/.swing2mcp/jvms/<pid>.properties} with its port and token.
 */
final class Apps {

    record AppInfo(long pid, int port, String token, String java, String command) {
    }

    private static final String AGENT_RESOURCE = "/swing2mcp/swing2mcp-agent.jar";

    private final Path home;
    private final Path registrations;
    private final Path logs;
    private AgentClient current;

    Apps(Path home) {
        this.home = home;
        this.registrations = home.resolve("jvms");
        this.logs = home.resolve("logs");
    }

    /** Running applications; registrations of dead processes are removed. */
    List<AppInfo> list() throws IOException {
        List<AppInfo> result = new ArrayList<>();
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
                long pid = Long.parseLong(props.getProperty("pid", "-1"));
                if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    Files.deleteIfExists(file);
                    continue;
                }
                result.add(new AppInfo(pid, Integer.parseInt(props.getProperty("port")), props.getProperty("token"),
                        props.getProperty("java", "?"), props.getProperty("command", "")));
            }
        }
        return result;
    }

    AgentClient connect(long pid) throws IOException {
        AppInfo app = find(pid).orElseThrow(() -> new IOException("No swing2mcp-enabled application with pid "
                + pid + ". Use list_apps to see running applications."));
        current = new AgentClient(app.pid(), app.port(), app.token());
        return current;
    }

    /** Connects to a running Java application, loading the agent into it first if needed. */
    String connectOrAttach(long pid) throws IOException, InterruptedException {
        if (find(pid).isPresent()) {
            connect(pid);
            return "Connected to pid " + pid + ".";
        }
        if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            throw new IOException("No process with pid " + pid + ". Use list_apps to see running applications.");
        }
        try {
            Attacher.loadAgent(pid, extractAgent());
        } catch (NoClassDefFoundError e) {
            throw new IOException("Attaching needs the MCP server to run on a JDK (jdk.attach module), not a JRE. "
                    + "Alternatively start the application with mvn swing2mcp:run.");
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (find(pid).isPresent()) {
                connect(pid);
                return "Loaded the agent into running pid " + pid + " and connected. The application kept its state.";
            }
            Thread.sleep(100);
        }
        throw new IOException("The agent was loaded into pid " + pid + " but did not report within 10 s.");
    }

    /** Java processes without the agent that connect_app could attach to; empty on a JRE. */
    List<Attacher.Jvm> otherJvms() throws IOException {
        Set<Long> withAgent = new HashSet<>();
        for (AppInfo app : list()) {
            withAgent.add(app.pid());
        }
        try {
            return Attacher.list().stream().filter(j -> !withAgent.contains(j.pid())).toList();
        } catch (NoClassDefFoundError e) {
            return List.of();
        }
    }

    private Optional<AppInfo> find(long pid) throws IOException {
        return list().stream().filter(a -> a.pid() == pid).findFirst();
    }

    /**
     * The connected application; connects automatically when exactly one application has the
     * agent. Never attaches on its own: loading the agent always needs an explicit connect_app.
     */
    AgentClient current() throws IOException {
        if (current != null && ProcessHandle.of(current.pid).map(ProcessHandle::isAlive).orElse(false)) {
            return current;
        }
        current = null;
        List<AppInfo> apps = list();
        if (apps.isEmpty()) {
            throw new IOException("No application is connected. Use list_apps, then connect_app with a pid, "
                    + "or start one with launch_app.");
        }
        if (apps.size() > 1) {
            throw new IOException("Several applications are running (pids "
                    + apps.stream().map(a -> String.valueOf(a.pid())).collect(Collectors.joining(", "))
                    + "). Choose one with connect_app.");
        }
        return connect(apps.get(0).pid());
    }

    Optional<Long> currentPid() {
        return Optional.ofNullable(current).map(c -> c.pid);
    }

    /**
     * Runs a shell command with the agent injected through JAVA_TOOL_OPTIONS, so it works
     * for {@code java -jar}, start scripts and IDE-generated command lines alike.
     */
    String launch(String command, Path cwd, Duration timeout) throws IOException, InterruptedException {
        Path agentJar = extractAgent();
        Files.createDirectories(logs);
        Path log = logs.resolve("app-" + System.currentTimeMillis() + ".log");

        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        ProcessBuilder builder = windows
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("/bin/sh", "-c", command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        Map<String, String> env = builder.environment();
        String existing = env.getOrDefault("JAVA_TOOL_OPTIONS", "");
        // The JVM splits JAVA_TOOL_OPTIONS on spaces but honours quotes (also on Java 8), which
        // matters for Windows user folders such as C:\Users\Ali Veli.
        String agentPath = agentJar.toString();
        if (agentPath.indexOf(' ') >= 0) {
            agentPath = "\"" + agentPath + "\"";
        }
        env.put("JAVA_TOOL_OPTIONS", (existing + " -javaagent:" + agentPath).trim());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(windows ? Path.of("NUL").toFile() : Path.of("/dev/null").toFile()));

        Set<Long> before = new HashSet<>();
        for (AppInfo app : list()) {
            before.add(app.pid());
        }
        Process process = builder.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Set<Long> family = new HashSet<>();
            family.add(process.pid());
            process.descendants().forEach(p -> family.add(p.pid()));
            AppInfo fallback = null;
            for (AppInfo app : list()) {
                if (family.contains(app.pid())) {
                    connect(app.pid());
                    return "Launched pid " + app.pid() + " (Java " + app.java() + "), agent connected.\nLog: " + log;
                }
                if (!before.contains(app.pid())) {
                    fallback = app;
                }
            }
            if (fallback != null && !process.isAlive()) {
                // e.g. the command handed the work to a detached JVM
                connect(fallback.pid());
                return "Launched pid " + fallback.pid() + " (Java " + fallback.java() + "), agent connected.\nLog: " + log;
            }
            if (!process.isAlive() && fallback == null) {
                return failure("The command exited with code " + process.exitValue()
                        + " before the agent started.", log);
            }
            Thread.sleep(100);
        }
        return failure("The agent did not report within " + timeout.toSeconds() + " s.", log);
    }

    String stop(long pid) throws IOException {
        ProcessHandle handle = ProcessHandle.of(pid)
                .orElseThrow(() -> new IOException("No process with pid " + pid));
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();
        try {
            handle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            handle.destroyForcibly();
        }
        if (current != null && current.pid == pid) {
            current = null;
        }
        Files.deleteIfExists(registrations.resolve(pid + ".properties"));
        return "Stopped pid " + pid + ".";
    }

    private static String failure(String message, Path log) throws IOException {
        List<String> lines = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
        List<String> tail = lines.subList(Math.max(0, lines.size() - 30), lines.size());
        throw new IOException(message + " Log: " + log + (tail.isEmpty() ? "" : "\n" + String.join("\n", tail)));
    }

    /** Copies the embedded agent jar to ~/.swing2mcp/agent/, named by content hash. */
    private Path extractAgent() throws IOException {
        String override = System.getProperty("swing2mcp.agentJar");
        if (override != null) {
            return Path.of(override).toAbsolutePath();
        }
        byte[] bytes;
        try (InputStream in = Apps.class.getResourceAsStream(AGENT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Agent jar not embedded; build with mvn package or set -Dswing2mcp.agentJar");
            }
            bytes = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        Path dir = home.resolve("agent");
        Path jar = dir.resolve("swing2mcp-agent-" + hash + ".jar");
        if (!Files.exists(jar)) {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "agent", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        return jar;
    }
}
