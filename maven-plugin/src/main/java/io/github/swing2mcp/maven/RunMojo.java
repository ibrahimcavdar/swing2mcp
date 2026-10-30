package io.github.swing2mcp.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Compiles the project and starts the application with the swing2mcp agent, so AI tools can
 * see and use it. The project's own jar and code are not changed: the agent is only present
 * in this run.
 *
 * <pre>
 *   mvn swing2mcp:run
 *   mvn swing2mcp:run -Dswing2mcp.javaHome=/path/to/jdk8
 *   mvn swing2mcp:run -Dswing2mcp.jar=target/app.jar
 * </pre>
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public class RunMojo extends AbstractMojo {

    /** Beyond this length the classpath goes into a manifest-only jar (Windows command-line limit). */
    private static final int MAX_INLINE_CLASSPATH = 8000;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true, required = true)
    private Map<String, Artifact> pluginArtifacts;

    /** Main class. If not set, the mainClass configured for another plugin (jar, assembly, exec...) is used. */
    @Parameter(property = "swing2mcp.mainClass")
    private String mainClass;

    /** Run this jar with {@code java -jar} instead of the project classpath. */
    @Parameter(property = "swing2mcp.jar")
    private File jar;

    /** JDK or JRE that runs the application, e.g. a Java 8 home. Default: the JVM running Maven. */
    @Parameter(property = "swing2mcp.javaHome")
    private File javaHome;

    /** Extra JVM options, separated by spaces, e.g. {@code -Xmx1g -Dprofile=dev}. */
    @Parameter(property = "swing2mcp.jvmArgs")
    private String jvmArgs;

    /** Program arguments, separated by spaces. */
    @Parameter(property = "swing2mcp.args")
    private String args;

    @Parameter(property = "swing2mcp.workingDirectory", defaultValue = "${project.basedir}")
    private File workingDirectory;

    /** Set to false to run the application without the agent. */
    @Parameter(property = "swing2mcp.agent", defaultValue = "true")
    private boolean agent;

    @Override
    public void execute() throws MojoExecutionException {
        List<String> command = new ArrayList<String>();
        command.add(Jvm.javaExecutable(javaHome).getPath());
        if (agent) {
            command.add("-javaagent:" + Jvm.pluginArtifact(pluginArtifacts, "swing2mcp-agent").getPath());
        }
        command.addAll(split(jvmArgs));
        String description;
        if (jar != null) {
            command.add("-jar");
            command.add(jar.getAbsolutePath());
            description = jar.getName();
        } else {
            String main = mainClass != null ? mainClass : findMainClass();
            command.add("-cp");
            command.add(classpath());
            command.add(main);
            description = main;
        }
        command.addAll(split(args));

        getLog().info("swing2mcp: starting " + description + (agent ? " with the agent" : " without the agent"));
        getLog().debug("swing2mcp: " + command);
        if (agent) {
            getLog().info("swing2mcp: AI tools can connect through the swing2mcp MCP server (see mvn swing2mcp:setup)");
        }
        run(command);
    }

    private void run(List<String> command) throws MojoExecutionException {
        final Process process;
        try {
            process = new ProcessBuilder(command).directory(workingDirectory).inheritIO().start();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot start " + command.get(0), e);
        }
        // Ctrl+C on Maven must not leave the application running.
        Thread stopper = new Thread(new Runnable() {
            public void run() {
                process.destroy();
            }
        }, "swing2mcp-stop");
        Runtime.getRuntime().addShutdownHook(stopper);
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new MojoExecutionException("Application exited with code " + exit);
            }
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(stopper);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down
            }
        }
    }

    private String classpath() throws MojoExecutionException {
        List<String> elements;
        try {
            elements = project.getRuntimeClasspathElements();
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot resolve the runtime classpath", e);
        }
        String inline = join(elements, File.pathSeparator);
        if (inline.length() <= MAX_INLINE_CLASSPATH) {
            return inline;
        }
        return pathingJar(elements).getAbsolutePath();
    }

    /** A jar that contains only a manifest whose Class-Path lists the real classpath. */
    private File pathingJar(List<String> elements) throws MojoExecutionException {
        StringBuilder classPath = new StringBuilder();
        for (String element : elements) {
            classPath.append(classPath.length() > 0 ? " " : "").append(new File(element).toURI().toString());
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString());
        File file = new File(project.getBuild().getDirectory(), "swing2mcp/classpath.jar");
        file.getParentFile().mkdirs();
        try {
            new JarOutputStream(new FileOutputStream(file), manifest).close();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write " + file, e);
        }
        return file;
    }

    /** Looks for a {@code <mainClass>} in any build plugin configuration (jar, assembly, exec...). */
    private String findMainClass() throws MojoExecutionException {
        for (Plugin plugin : project.getBuildPlugins()) {
            String found = findElement((Xpp3Dom) plugin.getConfiguration(), "mainClass");
            if (found == null) {
                for (org.apache.maven.model.PluginExecution execution : plugin.getExecutions()) {
                    found = findElement((Xpp3Dom) execution.getConfiguration(), "mainClass");
                    if (found != null) {
                        break;
                    }
                }
            }
            if (found != null) {
                getLog().info("swing2mcp: using mainClass " + found + " from " + plugin.getArtifactId());
                return found;
            }
        }
        throw new MojoExecutionException("No main class found. Set it with -Dswing2mcp.mainClass=com.example.Main "
                + "or <mainClass> in the plugin configuration.");
    }

    private static String findElement(Xpp3Dom dom, String name) {
        if (dom == null) {
            return null;
        }
        if (name.equals(dom.getName()) && dom.getValue() != null && dom.getValue().trim().length() > 0) {
            return dom.getValue().trim();
        }
        for (Xpp3Dom child : dom.getChildren()) {
            String found = findElement(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<String> split(String value) {
        if (value == null || value.trim().length() == 0) {
            return new ArrayList<String>();
        }
        return Arrays.asList(value.trim().split("\\s+"));
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(sb.length() > 0 ? separator : "").append(part);
        }
        return sb.toString();
    }
}
