package ai.mindconnect.agent.registry.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * The file behind a {@link RegistryItemType#PACKAGE} entry: everything that
 * has to arrive together for a set-up to work.
 *
 * <p>A useful agent is rarely one file. It points at an LLM config by name,
 * calls two sub-agents, and one of those runs a workflow — import the agent
 * alone and it is installed and broken. A package names the whole set, and
 * the import service installs it in the listed order.
 *
 * <p>Members come in two shapes, and a package may mix them:
 * <ul>
 *   <li>{@code includes} — ids of entries in the same registry index. Reuse:
 *       the same agent can be a member of three packages and exist once.</li>
 *   <li>{@code items} — full entries written into the package file itself, for
 *       parts that are only ever this package's.</li>
 * </ul>
 *
 * @param name        what the package is called
 * @param description what it sets up
 * @param version     the author's version string
 * @param includes    ids of index entries that belong to this package, in
 *                    install order
 * @param items       entries declared inline, installed after the includes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryPackage(
        String name,
        String description,
        String version,
        List<String> includes,
        List<RegistryEntry> items
) {

    public RegistryPackage {
        includes = includes == null ? List.of() : List.copyOf(includes);
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Whether this package would install nothing at all. */
    public boolean isEmpty() {
        return includes.isEmpty() && items.isEmpty();
    }

    /**
     * The members, in install order, with each include resolved against
     * {@code index}. An include naming an entry the index does not have is
     * dropped here and reported by the caller — a package must not be able to
     * abort an import halfway through because one of six members was renamed.
     *
     * @param index the index the package was listed in
     * @param unresolved collects the include ids that named nothing
     */
    public List<RegistryEntry> members(RegistryIndex index, List<String> unresolved) {
        List<RegistryEntry> members = new ArrayList<>();
        for (String include : includes) {
            index.find(include).ifPresentOrElse(members::add, () -> unresolved.add(include));
        }
        members.addAll(items);
        return members;
    }

    @JsonCreator
    static RegistryPackage fromJson(
            @JsonProperty("name")        String name,
            @JsonProperty("description") String description,
            @JsonProperty("version")     String version,
            @JsonProperty("includes")    List<String> includes,
            @JsonProperty("items")       List<RegistryEntry> items) {
        return new RegistryPackage(name, description, version, includes, items);
    }
}
