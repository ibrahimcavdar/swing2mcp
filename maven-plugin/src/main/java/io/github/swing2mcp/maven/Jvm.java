package io.github.swing2mcp.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/** Locating Java installations and the swing2mcp jars that ship with this plugin. */
final class Jvm {

    private Jvm() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    /** bin/java of the given home, or of the JVM running Maven when home is null. */
    static File javaExecutable(File home) throws MojoExecutionException {
        File base = home != null ? home : new File(System.getProperty("java.home"));
        File java = new File(base, "bin" + File.separator + (isWindows() ? "java.exe" : "java"));
        if (!java.isFile()) {
            throw new MojoExecutionException("No java executable in " + base);
        }
        return java;
    }

    /**
     * Major Java version of a home (8, 11, 17...), from its {@code release} file; -1 if unknown.
     * The home of a Java 8 JRE inside a JDK has the release file one level up.
     */
    static int majorVersion(File home) {
        for (File dir = home; dir != null; dir = dir.getParentFile()) {
            File release = new File(dir, "release");
            if (release.isFile()) {
                Properties props = new Properties();
                InputStream in = null;
                try {
                    in = new FileInputStream(release);
                    props.load(in);
                } catch (IOException e) {
                    return -1;
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                            // nothing to do
                        }
                    }
                }
                return parseMajor(props.getProperty("JAVA_VERSION", "").replace("\"", ""));
            }
            if (dir.equals(home.getParentFile())) {
                break;
            }
        }
        return -1;
    }

    static int parseMajor(String version) {
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        StringBuilder digits = new StringBuilder();
        for (char c : version.toCharArray()) {
            if (!Character.isDigit(c)) {
                break;
            }
            digits.append(c);
        }
        return digits.length() == 0 ? -1 : Integer.parseInt(digits.toString());
    }

    static File pluginArtifact(Map<String, Artifact> artifacts, String artifactId) throws MojoExecutionException {
        Artifact artifact = artifacts.get("io.github.swing2mcp:" + artifactId);
        if (artifact == null || artifact.getFile() == null) {
            throw new MojoExecutionException("Plugin dependency " + artifactId + " is missing");
        }
        return artifact.getFile();
    }
}
