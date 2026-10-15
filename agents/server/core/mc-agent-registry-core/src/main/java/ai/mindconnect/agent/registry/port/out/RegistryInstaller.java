package ai.mindconnect.agent.registry.port.out;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;

/**
 * Turns one fetched file into one stored entity. The seam that keeps the
 * registry from knowing what an agent or a workflow is.
 *
 * <p>One installer per {@link RegistryItemType}, contributed by the module
 * that owns the entity: the LLM-config installer sits with the gateway
 * adapters, the workflow installer with the workflow tools, and an
 * installation that has no workflow engine simply has no workflow installer —
 * its registry then lists workflows it cannot import, and says so, rather
 * than dragging the engine in as a dependency.
 *
 * <p>{@link RegistryItemType#PACKAGE} has no installer: a package is walked by
 * the import service, not installed.
 */
public interface RegistryInstaller {

    /** What this installer installs. */
    RegistryItemType type();

    /**
     * Whether this installation already has an entity of that name — asked
     * before fetching anything, so a screen can warn, and again at install
     * time, so the mode can be applied.
     */
    boolean exists(String name);

    /**
     * Installs {@code content} as the entity {@code entry} advertises.
     *
     * <p>The installer decides what "already there" means for its entity and
     * honours {@code mode}: {@link ImportMode#SKIP_EXISTING} returns a
     * {@link ImportedItem#skipped skipped} line without writing,
     * {@link ImportMode#OVERWRITE} replaces the stored entity while keeping
     * its local id, so that agents, aliases and sessions pointing at it keep
     * working.
     *
     * @param entry   the index line this came from
     * @param content the file's text, exactly as the repository holds it
     * @param mode    what to do about a name that is taken
     * @return what happened — never null
     * @throws Exception when the content cannot be read at all; the service
     *         turns it into a {@link ai.mindconnect.agent.registry.domain.ImportStatus#FAILED}
     *         line and carries on with the next member
     */
    ImportedItem install(RegistryEntry entry, String content, ImportMode mode) throws Exception;

    /**
     * Deletes the entity of the entry's name from this installation — how a
     * package is removed again. Whatever else points at it (an agent naming a
     * deleted LLM config, a workflow calling a deleted agent) is not this
     * method's to know; the person chose what to remove on the package's
     * Contents tab.
     *
     * <p>The default removes nothing and says so, for an installer written
     * before removal existed.
     *
     * @return {@link ImportedItem#removed removed}, or
     *         {@link ImportedItem#skipped skipped} when nothing of that name is here
     * @throws Exception when the store refuses; the service turns it into a
     *         {@code FAILED} line and carries on
     */
    default ImportedItem remove(RegistryEntry entry) throws Exception {
        return ImportedItem.skipped(entry, entry.name(), "this installation cannot remove "
                + entry.type().label().toLowerCase(java.util.Locale.ROOT) + "s");
    }

    /**
     * The names of this installer's stored entities that point at the entity
     * of that kind and name — the agents running on an LLM config, the
     * workflows calling an agent. Asked before a package is removed, so that
     * what something else still needs is not ticked for deletion.
     *
     * <p>The default knows of no references, for an installer written before
     * removal existed; an entity it cannot see into is then not protected.
     *
     * @param type the kind of the entity referred to
     * @param name its name
     * @return the referring entities' names; empty when none
     */
    default java.util.List<String> referencesTo(RegistryItemType type, String name) {
        return java.util.List.of();
    }
}
