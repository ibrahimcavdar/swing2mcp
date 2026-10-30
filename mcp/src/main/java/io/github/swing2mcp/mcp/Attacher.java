package io.github.swing2mcp.mcp;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Attach API access, kept in its own class: on a JRE without the jdk.attach module only
 * this class fails to load, and the rest of the server keeps working.
 */
final class Attacher {

    record Jvm(long pid, String name) {
    }

    private Attacher() {
    }

    /** Java processes of this OS user, except this server. */
    static List<Jvm> list() {
        long self = ProcessHandle.current().pid();
        List<Jvm> result = new ArrayList<>();
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            try {
                long pid = Long.parseLong(descriptor.id());
                if (pid != self) {
                    result.add(new Jvm(pid, descriptor.displayName()));
                }
            } catch (NumberFormatException ignored) {
                // not a pid-based id
            }
        }
        return result;
    }

    static void loadAgent(long pid, Path agentJar) throws IOException {
        VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(String.valueOf(pid));
        } catch (AttachNotSupportedException | IOException e) {
            throw new IOException("Cannot attach to pid " + pid + ": " + e.getMessage()
                    + ". It must be a Java process of the same OS user, and attaching must not be disabled "
                    + "(-XX:+DisableAttachMechanism). Alternatively start it with mvn swing2mcp:run.");
        }
        try {
            vm.loadAgent(agentJar.toString());
        } catch (AgentLoadException e) {
            // A Java 8 target reports success as "0", which newer attach clients misread as a failure.
            if (!"0".equals(e.getMessage())) {
                throw new IOException("The agent could not be loaded into pid " + pid + ": " + e.getMessage());
            }
        } catch (AgentInitializationException e) {
            throw new IOException("The agent failed to start inside pid " + pid + ": " + e.getMessage());
        } finally {
            vm.detach();
        }
    }
}
