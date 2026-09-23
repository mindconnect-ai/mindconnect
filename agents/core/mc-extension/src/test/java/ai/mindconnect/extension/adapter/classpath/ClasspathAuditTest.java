package ai.mindconnect.extension.adapter.classpath;

import ai.mindconnect.extension.domain.ExtensionRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathAuditTest {

    /** A provider a manifest names — managed wherever its jar is. */
    public static final class DeclaredTools {
    }

    @Test
    void exploded_classes_are_not_jars_and_never_unmanaged() {
        // The test classpath is directories: nothing here has a jar to be audited.
        assertThat(ClasspathAudit.jarOf(getClass())).isNull();
        assertThat(ClasspathAudit.unmanaged(getClass().getClassLoader(), List.of(Runnable.class),
                ExtensionRegistry.empty())).isEmpty();
    }

    @Test
    void the_group_comes_from_the_jar_s_pom_properties(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path shipped = jar(dir.resolve("shipped.jar"), "META-INF/maven/ai.mindconnect/mc-x/pom.properties",
                "groupId=ai.mindconnect\nartifactId=mc-x\n");
        Path foreign = jar(dir.resolve("foreign.jar"), "META-INF/maven/com.acme/acme/pom.properties",
                "groupId=com.acme\nartifactId=acme\n");
        Path bare = jar(dir.resolve("bare.jar"), "README", "no maven metadata");

        try (JarFile s = new JarFile(shipped.toFile()); JarFile f = new JarFile(foreign.toFile());
             JarFile b = new JarFile(bare.toFile())) {
            assertThat(ClasspathAudit.groupOf(s)).isEqualTo(ClasspathAudit.SHIPPED_GROUP);
            assertThat(ClasspathAudit.groupOf(f)).isEqualTo("com.acme");
            assertThat(ClasspathAudit.groupOf(b)).isNull();
        }
    }

    private static Path jar(Path file, String entry, String content) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry(entry));
            out.write(content.getBytes());
            out.closeEntry();
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(bytes.toByteArray());
        }
        return file;
    }
}
