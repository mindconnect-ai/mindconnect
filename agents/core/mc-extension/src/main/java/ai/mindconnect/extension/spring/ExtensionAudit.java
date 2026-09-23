package ai.mindconnect.extension.spring;

import ai.mindconnect.extension.adapter.classpath.ClasspathAudit;

import java.util.List;

/**
 * The result of the start-up audit, kept for the Extensions screen: the
 * jars that bring providers without a manifest.
 */
public record ExtensionAudit(List<ClasspathAudit.UnmanagedJar> unmanaged) {

    public ExtensionAudit {
        unmanaged = unmanaged == null ? List.of() : List.copyOf(unmanaged);
    }

    public static ExtensionAudit clean() {
        return new ExtensionAudit(List.of());
    }

    public boolean isClean() {
        return unmanaged.isEmpty();
    }
}
