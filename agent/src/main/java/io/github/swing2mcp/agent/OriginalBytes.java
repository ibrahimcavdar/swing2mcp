package io.github.swing2mcp.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads the class file a loaded class was defined from: its jar entry or its file, found through the
 * class's code source. Hot reload uses it when no earlier version was recorded, e.g. for a module that
 * runs from its jar while its freshly compiled classes are in another directory.
 *
 * Returns null when the original cannot be known for sure: no code source, an unsupported location,
 * or a file in a directory that was rewritten after the JVM started.
 */
final class OriginalBytes {

    private final long jvmStart;
    private final Map<File, JarFile> jars = new HashMap<File, JarFile>();

    OriginalBytes(long jvmStart) {
        this.jvmStart = jvmStart;
    }

    byte[] of(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null) {
                return null;
            }
            String entry = type.getName().replace('.', '/') + ".class";
            if ("file".equals(location.getProtocol())) {
                File file = new File(new URI(location.toString()));
                if (file.isDirectory()) {
                    File classFile = new File(file, entry.replace('/', File.separatorChar));
                    // A file written after the JVM started may not be what it loaded.
                    return classFile.isFile() && classFile.lastModified() <= jvmStart ? read(new FileInputStream(classFile)) : null;
                }
                if (file.isFile() && file.lastModified() <= jvmStart) {
                    JarFile jar = jar(file);
                    JarEntry jarEntry = jar.getJarEntry(entry);
                    return jarEntry == null ? null : read(jar.getInputStream(jarEntry));
                }
                return null;
            }
            if ("jar".equals(location.getProtocol())) {
                // e.g. a nested jar of a Spring Boot executable jar: let its URL handler read the entry
                URLConnection connection = new URL(location, entry).openConnection();
                connection.setUseCaches(false);
                return read(connection.getInputStream());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    void close() {
        for (JarFile jar : jars.values()) {
            try {
                jar.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
        jars.clear();
    }

    private JarFile jar(File file) throws IOException {
        JarFile jar = jars.get(file);
        if (jar == null) {
            jar = new JarFile(file);
            jars.put(file, jar);
        }
        return jar;
    }

    private static byte[] read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
