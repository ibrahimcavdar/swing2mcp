package io.github.ibrahimcavdar.swing2mcp.mcp;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Attach API access. The API is part of a JDK, not of the Java platform: a module (jdk.attach) since
 * Java 9, the file lib/tools.jar on Java 8. It is used through reflection, so that the server compiles
 * for Java 8 and starts on a JRE too, where only attaching is unavailable.
 */
final class Attacher {

    static final class Jvm {
        final long pid;
        final String name;

        Jvm(long pid, String name) {
            this.pid = pid;
            this.name = name;
        }
    }

    /** This Java has no Attach API: it is a JRE, or the module was left out. */
    static final class Unavailable extends Exception {
        Unavailable(Throwable cause) {
            super(cause);
        }
    }

    private static final String VIRTUAL_MACHINE = "com.sun.tools.attach.VirtualMachine";
    private static ClassLoader toolsJar;

    private Attacher() {
    }

    /** Java processes of this OS user, except this server. */
    static List<Jvm> list() throws Unavailable {
        Class<?> vm = virtualMachine();
        long self = Processes.currentPid();
        List<Jvm> result = new ArrayList<Jvm>();
        try {
            for (Object descriptor : (List<?>) vm.getMethod("list").invoke(null)) {
                Class<?> type = vm.getClassLoader() == null
                        ? Class.forName("com.sun.tools.attach.VirtualMachineDescriptor")
                        : Class.forName("com.sun.tools.attach.VirtualMachineDescriptor", true, vm.getClassLoader());
                try {
                    long pid = Long.parseLong((String) type.getMethod("id").invoke(descriptor));
                    if (pid != self) {
                        result.add(new Jvm(pid, (String) type.getMethod("displayName").invoke(descriptor)));
                    }
                } catch (NumberFormatException ignored) {
                    // not a pid-based id
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new Unavailable(e);
        }
        return result;
    }

    /**
     * Loads the agent into a running JVM. Returns null, or what the attach client complained about when
     * that may still have been a success: attach clients and target JVMs of different Java versions
     * misread each other's answer (a Java 8 target says "0", which newer clients take for a failure; a
     * newer target answers in words, which a Java 8 client cannot parse). The caller then sees whether
     * the agent reports.
     */
    static String loadAgent(long pid, Path agentJar) throws IOException, Unavailable {
        Class<?> type = virtualMachine();
        Object vm;
        try {
            vm = type.getMethod("attach", String.class).invoke(null, String.valueOf(pid));
        } catch (InvocationTargetException e) {
            throw new IOException("Cannot attach to pid " + pid + ": " + e.getCause().getMessage()
                    + ". It must be a Java process of the same OS user, and attaching must not be disabled "
                    + "(-XX:+DisableAttachMechanism). Alternatively start it with mvn swing2mcp:run.");
        } catch (ReflectiveOperationException e) {
            throw new Unavailable(e);
        }
        try {
            type.getMethod("loadAgent", String.class).invoke(vm, agentJar.toString());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            String kind = cause.getClass().getSimpleName();
            String message = String.valueOf(cause.getMessage());
            if (kind.equals("AgentInitializationException")) {
                throw new IOException("The agent failed to start inside pid " + pid + ": " + message);
            }
            // AgentLoadException ("0") from a newer client on a Java 8 target, IOException ("Non-numeric
            // value found") from a Java 8 client on a newer target: both can be a success.
            return message;
        } catch (ReflectiveOperationException e) {
            throw new Unavailable(e);
        } finally {
            try {
                type.getMethod("detach").invoke(vm);
            } catch (ReflectiveOperationException ignored) {
                // the target may already be gone
            }
        }
        return null;
    }

    private static synchronized Class<?> virtualMachine() throws Unavailable {
        try {
            return Class.forName(VIRTUAL_MACHINE);
        } catch (ClassNotFoundException | LinkageError notOnClasspath) {
            // Java 8: the API is in the JDK's lib/tools.jar; java.home is the JDK's jre folder
            try {
                if (toolsJar == null) {
                    File home = new File(System.getProperty("java.home"));
                    File jar = new File(home.getParentFile(), "lib/tools.jar");
                    if (!jar.isFile()) {
                        jar = new File(home, "lib/tools.jar");
                    }
                    if (!jar.isFile()) {
                        throw new Unavailable(notOnClasspath);
                    }
                    toolsJar = new URLClassLoader(new URL[]{jar.toURI().toURL()}, Attacher.class.getClassLoader());
                }
                return Class.forName(VIRTUAL_MACHINE, true, toolsJar);
            } catch (ClassNotFoundException | LinkageError | IOException e) {
                throw new Unavailable(e);
            }
        }
    }
}
