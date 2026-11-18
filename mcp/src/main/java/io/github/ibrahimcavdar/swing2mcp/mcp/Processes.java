package io.github.ibrahimcavdar.swing2mcp.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * What the server needs to know about other processes. Java 9+ has ProcessHandle for this; it is used
 * through reflection when it is there, so that the same jar also runs on Java 8, where operating system
 * commands and the agent's port stand in.
 */
final class Processes {

    private static final Class<?> HANDLE = handleClass();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private Processes() {
    }

    private static Class<?> handleClass() {
        try {
            return Class.forName("java.lang.ProcessHandle");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    static boolean isWindows() {
        return WINDOWS;
    }

    static long currentPid() {
        // "pid@host" on every JVM in practice; the method that returns the pid directly came with Java 10
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        try {
            return Long.parseLong(at < 0 ? name : name.substring(0, at));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Whether the application with this pid is still running. On Java 8 the agent's port answers that
     * for applications that have the agent (port &gt; 0): cheap, and asked many times per second.
     */
    static boolean isAlive(long pid, int agentPort) {
        Object handle = handle(pid);
        if (HANDLE != null) {
            return handle != null && Boolean.TRUE.equals(call(handle, "isAlive"));
        }
        if (agentPort > 0) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), agentPort), 500);
                return true;
            } catch (IOException e) {
                return false;
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        try {
            if (WINDOWS) {
                return run("tasklist", "/FI", "PID eq " + pid, "/NH", "/FO", "CSV").contains("\"" + pid + "\"");
            }
            return exitCode("kill", "-0", String.valueOf(pid)) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Waits until the process is gone; false if it is still there after the time. */
    static boolean waitForExit(long pid, int agentPort, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (isAlive(pid, agentPort)) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** The pids of a process we started and of its descendants, or null where Java cannot tell (Java 8). */
    static Set<Long> family(Process process) {
        if (HANDLE == null) {
            return null;
        }
        try {
            Object handle = Process.class.getMethod("toHandle").invoke(process);
            Set<Long> pids = new HashSet<Long>();
            pids.add((Long) call(handle, "pid"));
            for (Iterator<?> it = ((Stream<?>) call(handle, "descendants")).iterator(); it.hasNext(); ) {
                pids.add((Long) call(it.next(), "pid"));
            }
            return pids;
        } catch (Exception e) {
            return null;
        }
    }

    /** Ends a process and its descendants: politely first, by force if it is still there after 5 s. */
    static void destroyTree(long pid) throws IOException {
        if (HANDLE != null) {
            Object handle = handle(pid);
            if (handle == null) {
                throw new IOException("No process with pid " + pid);
            }
            for (Iterator<?> it = ((Stream<?>) call(handle, "descendants")).iterator(); it.hasNext(); ) {
                call(it.next(), "destroy");
            }
            call(handle, "destroy");
            try {
                ((Future<?>) call(handle, "onExit")).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                call(handle, "destroyForcibly");
            }
            return;
        }
        if (!isAlive(pid, 0)) {
            throw new IOException("No process with pid " + pid);
        }
        if (WINDOWS) {
            // Windows has no polite signal for a process without a console of its own
            run("taskkill", "/PID", String.valueOf(pid), "/T", "/F");
        } else {
            run("kill", String.valueOf(pid));
            if (!waitForExit(pid, 0, 5000)) {
                run("kill", "-9", String.valueOf(pid));
            }
        }
        waitForExit(pid, 0, 5000);
    }

    private static Object handle(long pid) {
        if (HANDLE == null) {
            return null;
        }
        try {
            Optional<?> found = (Optional<?>) HANDLE.getMethod("of", long.class).invoke(null, pid);
            return found.isPresent() ? found.get() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object call(Object handle, String method) {
        try {
            Method m = HANDLE.getMethod(method);
            return m.invoke(handle);
        } catch (Exception e) {
            throw new IllegalStateException("ProcessHandle." + method + " failed", e);
        }
    }

    private static String run(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[4096];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            output.write(buffer, 0, n);
        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new String(output.toByteArray());
    }

    private static int exitCode(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[4096];
        while (in.read(buffer) >= 0) {
            // drain
        }
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
