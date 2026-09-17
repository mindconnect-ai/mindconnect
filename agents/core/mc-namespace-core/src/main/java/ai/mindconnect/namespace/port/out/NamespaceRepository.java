package ai.mindconnect.namespace.port.out;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.namespace.domain.Actor;
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

    /**
     * The namespaces {@code who} is listed in, in either role, ordered by id.
     * Matching is the record's business ({@code NamespaceDefinition.isMember}):
     * an entry is an e-mail, or a user id in the records written before that
     * was the rule.
     */
    List<NamespaceDefinition> findFor(Actor who);

    /** Creates or replaces. */
    void save(NamespaceDefinition namespace);

    /**
     * Creates, atomically: false — and nothing written — when a namespace with this id
     * exists. Two users creating the same id at the same moment get one namespace, not
     * the second one's record over the first's.
     */
    boolean insert(NamespaceDefinition namespace);

    /** Removes the record — not the namespace's data, which is the stores' business. */
    boolean deleteById(Namespace id);
}
