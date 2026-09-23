package ai.mindconnect.extension.adapter.classpath;

import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Names what the classpath offers through {@code ServiceLoader} without a
 * manifest to answer for it. The shipped modules are exempt — they are the
 * host, not extensions of it — and so is every provider a manifest lists
 * under {@code contributes}, wherever its jar. What remains is a jar that
 * would be wired without anybody having declared what it brings: a module
 * from before manifests existed, or one that forgot.
 *
 * <p>Provider classes are looked at, never instantiated: the audit runs
 * before the runtime is built, and a provider that binds on construction
 * must not bind here.
 */
public final class ClasspathAudit {

    private static final Logger log = LoggerFactory.getLogger(ClasspathAudit.class);

    /** The Maven group of the host's own modules; a jar of that group is shipped, whatever it declares. */
    public static final String SHIPPED_GROUP = "ai.mindconnect";

    /** A jar with providers and no manifest: its file name, its Maven group if the jar says, and the providers. */
    public record UnmanagedJar(String jar, String groupId, List<String> providers) {
        public UnmanagedJar {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }
    }

    private ClasspathAudit() {
    }

    /**
     * @param classLoader where to look
     * @param spiTypes    the service interfaces the host loads from the classpath ({@code ToolFactory}, …)
     * @param registry    the manifests found, whose declared providers are managed wherever they live
     */
    public static List<UnmanagedJar> unmanaged(ClassLoader classLoader, List<Class<?>> spiTypes,
                                               ExtensionRegistry registry) {
        Set<String> declared = new HashSet<>();
        for (Extension extension : registry.all()) {
            declared.addAll(extension.manifest().contributes().tools().providers());
            declared.addAll(extension.manifest().contributes().features());
        }
        Map<Path, List<String>> providersByJar = new LinkedHashMap<>();
        for (Class<?> spi : spiTypes) {
            for (ServiceLoader.Provider<?> provider : ServiceLoader.load(spi, classLoader).stream().toList()) {
                Class<?> type = provider.type();
                if (declared.contains(type.getName())) continue;
                Path jar = jarOf(type);
                if (jar == null) continue;
                providersByJar.computeIfAbsent(jar, j -> new ArrayList<>()).add(type.getName());
            }
        }
        List<UnmanagedJar> unmanaged = new ArrayList<>();
        providersByJar.forEach((jar, providers) -> {
            try (JarFile file = new JarFile(jar.toFile())) {
                if (file.getJarEntry(ClasspathManifests.RESOURCE) != null) return;   // an extension: managed
                String group = groupOf(file);
                if (SHIPPED_GROUP.equals(group)) return;                            // the host itself
                unmanaged.add(new UnmanagedJar(jar.getFileName().toString(), group, providers));
            } catch (IOException e) {
                log.debug("Cannot open {} for the extension audit: {}", jar, e.toString());
            }
        });
        return List.copyOf(unmanaged);
    }

    /** The jar the class was loaded from, or null for exploded classes and classes without a code source. */
    static Path jarOf(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            URL location = source.getLocation();
            if (!"file".equals(location.getProtocol())) return null;
            Path path = Path.of(location.toURI());
            return Files.isRegularFile(path) && path.toString().endsWith(".jar") ? path : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** The Maven group from the jar's {@code META-INF/maven/<group>/<artifact>/pom.properties}, or null. */
    static String groupOf(JarFile jar) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                Properties properties = new Properties();
                try (InputStream in = jar.getInputStream(entry)) {
                    properties.load(in);
                }
                return properties.getProperty("groupId");
            }
        }
        return null;
    }
}
