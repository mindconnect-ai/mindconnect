package ai.mindconnect.namespace.port.out;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;

import java.util.List;
import java.util.Optional;

/**
 * The namespaces of this installation. Installation-wide — an adapter is
 * never bound to a namespace, it is the store that says which namespaces
 * exist.
 */
public interface NamespaceRepository {

    Optional<NamespaceDefinition> findById(Namespace id);

    /** Every namespace, ordered by id. */
    List<NamespaceDefinition> findAll();

    /** The namespaces {@code user} is a member of, ordered by id. */
    List<NamespaceDefinition> findByMember(UserId user);

    /** Creates or replaces. */
    void save(NamespaceDefinition namespace);

    /** Removes the record — not the namespace's data, which is the stores' business. */
    boolean deleteById(Namespace id);
}
