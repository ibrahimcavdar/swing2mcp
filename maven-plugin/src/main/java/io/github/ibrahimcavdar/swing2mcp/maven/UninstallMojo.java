package io.github.ibrahimcavdar.swing2mcp.maven;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Undoes {@code swing2mcp:setup}: removes the server's entry from the settings of the AI tools, for the
 * user and in this project, and deletes the {@code ~/.swing2mcp} directory. Other entries in those
 * settings are kept. The plugin line in the pom is the only thing left to remove by hand.
 *
 * <pre>
 *   mvn swing2mcp:uninstall
 * </pre>
 */
@Mojo(name = "uninstall", aggregator = true, threadSafe = true)
public class UninstallMojo extends AbstractMojo {

    /** One thing uninstall is about to remove. */
    private abstract static class Step {
        final String description;

        Step(String description) {
            this.description = description;
        }

        abstract void run() throws IOException;
    }

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File basedir;

    /** False with {@code mvn -B}: then uninstall does not ask before removing. */
    @Parameter(defaultValue = "${settings.interactiveMode}", readonly = true)
    private boolean interactive;

    /** Name under which setup registered the server. */
    @Parameter(property = "swing2mcp.serverName", defaultValue = "swing")
    private String serverName;

    /** The user's home directory; only changed by tests. */
    @Parameter(property = "swing2mcp.userHome", defaultValue = "${user.home}")
    private File userHome;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void execute() throws MojoExecutionException {
        List<Step> plan = new ArrayList<Step>();
        try {
            jsonEntry(plan, "Claude Code", new File(basedir, ".mcp.json"), "mcpServers");
            jsonEntry(plan, "Cursor", new File(userHome, ".cursor/mcp.json"), "mcpServers");
            jsonEntry(plan, "Cursor", new File(basedir, ".cursor/mcp.json"), "mcpServers");
            jsonEntry(plan, "OpenCode", new File(userHome, ".config/opencode/opencode.json"), "mcp");
            jsonEntry(plan, "OpenCode", new File(basedir, "opencode.json"), "mcp");
            tomlTable(plan, new File(userHome, ".codex/config.toml"));
            tomlTable(plan, new File(basedir, ".codex/config.toml"));
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read the settings of an AI tool", e);
        }
        final File claude = SetupMojo.onPath("claude");
        if (claude != null) {
            plan.add(new Step("Claude Code: remove \"" + serverName + "\" from your user settings by running: "
                    + "claude mcp remove --scope user " + serverName) {
                void run() throws IOException {
                    String[] removed = SetupMojo.run(claude, "mcp", "remove", "--scope", "user", serverName);
                    getLog().info("0".equals(removed[0])
                            ? "swing2mcp: removed '" + serverName + "' from Claude Code (user settings)"
                            : "swing2mcp: Claude Code had no '" + serverName + "' in its user settings");
                }
            });
        }
        final File home = new File(userHome, ".swing2mcp");
        if (home.exists()) {
            plan.add(new Step("delete " + home + " (the MCP server, the agent, logs)") {
                void run() {
                    List<File> left = new ArrayList<File>();
                    delete(home, left);
                    if (left.isEmpty()) {
                        getLog().info("swing2mcp: deleted " + home);
                    } else {
                        getLog().warn("swing2mcp: could not delete " + left.size() + " file(s), probably because an "
                                + "application or AI tool still uses them. Close it and run uninstall again. First: "
                                + left.get(0));
                    }
                }
            });
        }

        if (plan.isEmpty()) {
            getLog().info("swing2mcp: nothing to remove; setup has left nothing on this machine or in this project.");
            return;
        }
        getLog().info("swing2mcp uninstall will:");
        for (Step step : plan) {
            getLog().info("  - " + step.description);
        }
        getLog().info("Other entries in those settings are kept.");
        if (interactive && !confirm()) {
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
        getLog().info("swing2mcp: done. What is left is the plugin in your pom.xml; remove it if you no longer "
                + "want swing2mcp:run.");
    }

    private boolean confirm() {
        System.out.print("Go ahead? [Y/n]: ");
        System.out.flush();
        String answer;
        try {
            answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (IOException e) {
            return false;
        }
        if (answer == null) {
            return false;
        }
        answer = answer.trim().toLowerCase(Locale.ROOT);
        return answer.length() == 0 || answer.equals("y") || answer.equals("yes");
    }

    /** Plans the removal of our entry from a JSON settings file, if the file has one. */
    private void jsonEntry(List<Step> plan, String tool, final File file, final String section) throws IOException {
        if (!file.isFile()) {
            return;
        }
        JsonElement servers = SetupMojo.read(file).get(section);
        if (servers == null || !servers.isJsonObject() || !servers.getAsJsonObject().has(serverName)) {
            return;
        }
        plan.add(new Step(tool + ": remove \"" + serverName + "\" from " + file) {
            void run() throws IOException {
                JsonObject root = SetupMojo.read(file);
                JsonObject entries = root.getAsJsonObject(section);
                entries.remove(serverName);
                if (entries.size() == 0) {
                    root.remove(section);
                }
                // A file that setup created holds nothing else: it goes, with the folder made for it.
                if (root.size() == 0 || (root.size() == 1 && root.has("$schema"))) {
                    Files.delete(file.toPath());
                    removeIfEmpty(file.getParentFile());
                    getLog().info("swing2mcp: deleted " + file + " (it held nothing else)");
                } else {
                    Files.write(file.toPath(), (gson.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8));
                    getLog().info("swing2mcp: removed '" + serverName + "' from " + file);
                }
            }
        });
    }

    /** Plans the removal of our [mcp_servers.NAME] table from a Codex settings file, if it has one. */
    private void tomlTable(List<Step> plan, final File file) throws IOException {
        if (!file.isFile()) {
            return;
        }
        final String header = "[mcp_servers." + serverName + "]";
        final String subPrefix = "[mcp_servers." + serverName + ".";
        final String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        boolean present = false;
        for (String line : text.split("\r?\n", -1)) {
            present |= line.trim().equals(header);
        }
        if (!present) {
            return;
        }
        plan.add(new Step("Codex: remove " + header + " from " + file) {
            void run() throws IOException {
                StringBuilder kept = new StringBuilder();
                boolean skipping = false;
                for (String line : text.split("\r?\n", -1)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("[")) {
                        skipping = trimmed.equals(header) || trimmed.startsWith(subPrefix);
                    }
                    if (!skipping) {
                        kept.append(line).append('\n');
                    }
                }
                String rest = kept.toString().replaceAll("\\s+$", "");
                if (rest.length() == 0) {
                    Files.delete(file.toPath());
                    removeIfEmpty(file.getParentFile());
                    getLog().info("swing2mcp: deleted " + file + " (it held nothing else)");
                } else {
                    Files.write(file.toPath(), (rest + "\n").getBytes(StandardCharsets.UTF_8));
                    getLog().info("swing2mcp: removed " + header + " from " + file);
                }
            }
        });
    }

    /** Removes a settings folder that is empty now; never the project or the home directory itself. */
    private void removeIfEmpty(File dir) {
        if (dir == null || dir.equals(basedir) || dir.equals(userHome)) {
            return;
        }
        String[] children = dir.list();
        if (children != null && children.length == 0) {
            dir.delete();
        }
    }

    private static void delete(File file, List<File> left) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child, left);
            }
        }
        if (!file.delete() && file.exists() && !file.isDirectory()) {
            left.add(file);
        }
    }
}
