package ai.mindconnect.workflow.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal classpath and file utilities.
 * Uses only the JDK standard library — no Apache Commons required.
 */
public class ResourceUtil {

    private ResourceUtil() {}

    /**
     * Reads a classpath resource as a UTF-8 string.
     *
     * @param resourcePath path relative to classpath root, e.g. {@code /workflows/myflow.json}
     * @throws IllegalArgumentException when the resource does not exist
     * @throws RuntimeException         on I/O error
     */
    public static String readFromClasspath(String resourcePath) {
        try (InputStream is = ResourceUtil.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read classpath resource: " + resourcePath, e);
        }
    }

    /**
     * Writes {@code content} to {@code path}, creating parent directories as needed.
     */
    public static Path writeString(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            return Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + path, e);
        }
    }
}
