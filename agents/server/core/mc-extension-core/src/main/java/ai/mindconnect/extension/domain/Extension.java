package ai.mindconnect.extension.domain;

import java.util.Objects;

/**
 * An extension the host found: its manifest and where it came from — the
 * jar's file name, or whatever the loader can say about the resource. The
 * origin is for people (the Extensions screen, the log); nothing decides by it.
 */
public record Extension(ExtensionManifest manifest, String origin) {

    public Extension {
        Objects.requireNonNull(manifest, "manifest");
        origin = origin == null ? "" : origin;
    }

    public ExtensionId id() {
        return manifest.id();
    }
}
