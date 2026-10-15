package ai.mindconnect.agent.registry.port.out;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;

import java.util.List;
import java.util.Optional;

/**
 * The registries this installation knows. An adapter is bound to one
 * namespace when it is built; a source's id is unique within it.
 */
public interface RegistrySourceRepository {

    RegistrySource save(RegistrySource source);

    Optional<RegistrySource> findById(RegistrySourceId id);

    /** Every configured source, enabled or not. */
    List<RegistrySource> findAll();

    /** Only the sources that are actually read. */
    default List<RegistrySource> findEnabled() {
        return findAll().stream().filter(RegistrySource::enabled).toList();
    }

    void deleteById(RegistrySourceId id);
}
