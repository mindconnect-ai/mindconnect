package ai.mindconnect.vectorstore.tools;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One namespace's {@link VectorStoreTemplate}s and {@link VectorStoreInstance}s.
 * Instances are registered on the fly by the tools; templates are managed in
 * the admin UI (or seeded). On files ({@link FileVectorStoreRegistry}) under
 * file persistence, in Postgres ({@link PgVectorStoreRegistry}) under Postgres
 * persistence — {@link VectorStores#registry} hands out the one that applies.
 *
 * <p>A record is found by its {@link #key(String) key}, not by its exact
 * name: {@code Chat Uploads} and {@code chat-uploads} are one template.
 */
public interface VectorStoreRegistry {

    /**
     * What a name is stored under: lower case, every character but
     * {@code [a-z0-9._-]} a dash — the file name on files, the id in the database.
     */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    }

    // ── templates ──────────────────────────────────────────────────────────

    List<VectorStoreTemplate> templates();

    Optional<VectorStoreTemplate> template(String name);

    /**
     * Saves the template: the version it carries is checked against the stored one
     * and it is stored one higher, as one step ({@code null}: no check).
     *
     * @return the template as stored, with its new version
     * @throws ai.mindconnect.common.StaleVersionException when it was saved by someone else meanwhile
     */
    VectorStoreTemplate saveTemplate(VectorStoreTemplate template);

    void deleteTemplate(String name);

    // ── instances ──────────────────────────────────────────────────────────

    List<VectorStoreInstance> instances();

    Optional<VectorStoreInstance> instance(String name);

    /**
     * Registers the instance if unknown; an existing record wins (settings own the
     * store). Looking and writing are one step, so of two concurrent registrations
     * of one name exactly one is written and both callers get it back.
     */
    VectorStoreInstance registerInstance(VectorStoreInstance candidate);

    /** Overwrites an instance record — instances may diverge from their template. */
    void saveInstance(VectorStoreInstance instance);

    /** Instances of one scope (e.g. all SESSION stores of a session id); {@code scopeRef} null: all of the scope. */
    default List<VectorStoreInstance> instances(VectorStoreInstance.Scope scope, String scopeRef) {
        return instances().stream()
                .filter(i -> i.scope() == scope
                        && (scopeRef == null || scopeRef.equals(i.scopeRef())))
                .toList();
    }

    void deleteInstance(String name);
}
