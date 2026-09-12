package ai.mindconnect.agent.registry.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * What a registry repository advertises: its {@code registry.json}.
 *
 * <p>One file, read in one request, listing everything the repository offers.
 * A registry with a thousand entries would want paging; a registry is a Git
 * repository somebody curates by hand, and those do not have a thousand
 * entries.
 *
 * @param schemaVersion the index format — {@link #SCHEMA_VERSION} is what this
 *                      code reads. A higher one is read anyway, on the
 *                      assumption that the format grows by adding fields;
 *                      unknown fields are ignored throughout
 * @param name          what the registry calls itself
 * @param description   what it is for
 * @param entries       everything on offer
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryIndex(
        int schemaVersion,
        String name,
        String description,
        List<RegistryEntry> entries
) {

    /** The index format this code was written against. */
    public static final int SCHEMA_VERSION = 1;

    public RegistryIndex {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (schemaVersion <= 0) schemaVersion = SCHEMA_VERSION;
    }

    public static RegistryIndex empty() {
        return new RegistryIndex(SCHEMA_VERSION, null, null, List.of());
    }

    /** The entry of that id. */
    public Optional<RegistryEntry> find(String entryId) {
        if (entryId == null) return Optional.empty();
        return entries.stream().filter(entry -> entry.id().equals(entryId)).findFirst();
    }

    /** The entries matching {@code query} and, when given, {@code type} — in index order. */
    public List<RegistryEntry> search(String query, RegistryItemType type) {
        return entries.stream()
                .filter(entry -> type == null || entry.type() == type)
                .filter(entry -> entry.matches(query))
                .toList();
    }

    @JsonCreator
    static RegistryIndex fromJson(
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("name")          String name,
            @JsonProperty("description")   String description,
            @JsonProperty("entries")       List<RegistryEntry> entries) {
        return new RegistryIndex(schemaVersion, name, description, entries);
    }
}
