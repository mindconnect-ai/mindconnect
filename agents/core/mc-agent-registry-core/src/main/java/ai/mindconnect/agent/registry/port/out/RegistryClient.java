package ai.mindconnect.agent.registry.port.out;

import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryPackage;
import ai.mindconnect.agent.registry.domain.RegistrySource;

/**
 * Reads a registry repository. Two calls, because a registry is two things:
 * an index, and the files it points at.
 *
 * <p>The port says nothing about GitHub. The default adapter fetches raw
 * files over HTTPS, a test hands back strings from a map, and an installation
 * that keeps its registry on a mounted volume can read from disk — the import
 * service never learns the difference.
 */
public interface RegistryClient {

    /**
     * The source's index.
     *
     * @throws ai.mindconnect.agent.registry.domain.RegistryException when the
     *         repository, the ref or the index file cannot be read, or the
     *         index does not parse
     */
    RegistryIndex fetchIndex(RegistrySource source);

    /**
     * One file from the repository, as text.
     *
     * @param path repository-relative, as an entry carries it
     * @throws ai.mindconnect.agent.registry.domain.RegistryException when the
     *         file cannot be read
     */
    String fetchText(RegistrySource source, String path);

    /**
     * The package manifest at {@code path}.
     *
     * <p>Parsing lives here rather than in the import service for the same
     * reason the index does: the core module knows the shape of a manifest,
     * not the library that reads JSON.
     *
     * @throws ai.mindconnect.agent.registry.domain.RegistryException when the
     *         file cannot be read or does not parse
     */
    RegistryPackage fetchPackage(RegistrySource source, String path);

    /**
     * Forgets whatever is cached for this source, so the next read goes to the
     * repository. What the "refresh" button on a registry screen calls: a
     * registry moves when its owner pushes, not on a schedule this
     * installation knows.
     */
    default void refresh(RegistrySource source) {
        // Adapters without a cache have nothing to forget.
    }
}
