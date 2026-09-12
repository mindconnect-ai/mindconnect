package ai.mindconnect.agent.registry.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;

/**
 * One line of a registry index: something importable, and where its file sits
 * in the repository.
 *
 * <p>The entry is the advertisement, not the thing. It carries what a person
 * needs to decide — what this is, who wrote it, what it drags in — and a
 * {@link #path} to the file that actually holds the entity. The file is
 * fetched only when somebody imports.
 *
 * @param id          address of this entry within its registry, referenced by
 *                    {@link #requires} and by a package's item list
 * @param type        what it installs
 * @param name        the entity's name, which is also the name it is installed
 *                    under — the index says it so a screen can warn about a
 *                    collision before fetching anything
 * @param description one or two sentences for the list
 * @param version     the entry author's version string, free-form
 * @param path        path of the entity file inside the repository
 * @param tags        free labels for filtering
 * @param author      who wrote it
 * @param homepage    where to read more
 * @param requires    ids of entries in the same index that have to be installed
 *                    first — the LLM alias an agent points at, the sub-agents
 *                    it calls. Imported in order, before this entry
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryEntry(
        String id,
        RegistryItemType type,
        String name,
        String description,
        String version,
        String path,
        List<String> tags,
        String author,
        String homepage,
        List<String> requires
) {

    public RegistryEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A registry entry needs an id");
        }
        if (type == null) {
            throw new IllegalArgumentException("Registry entry '" + id + "' has no type");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Registry entry '" + id + "' has no path");
        }
        id = id.strip();
        path = path.strip();
        if (path.startsWith("/")) path = path.substring(1);
        if (name == null || name.isBlank()) name = id;
        tags = tags == null ? List.of() : List.copyOf(tags);
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    /** Does this entry's name, description or any tag contain {@code query}? Blank matches everything. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return contains(id, needle) || contains(name, needle) || contains(description, needle)
                || contains(author, needle)
                || tags.stream().anyMatch(tag -> contains(tag, needle));
    }

    /** "Agent · v1.2.0 · by someone" — the subtitle a list draws under the name. */
    @JsonIgnore
    public String subtitle() {
        StringBuilder text = new StringBuilder(type.label());
        if (version != null && !version.isBlank()) text.append(" · ").append(version);
        if (author != null && !author.isBlank()) text.append(" · by ").append(author);
        return text.toString();
    }

    private static boolean contains(String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }

    /** Jackson: {@code requires} also accepts a single string, which is what people write. */
    @JsonCreator
    static RegistryEntry fromJson(
            @JsonProperty("id")          String id,
            @JsonProperty("type")        RegistryItemType type,
            @JsonProperty("name")        String name,
            @JsonProperty("description") String description,
            @JsonProperty("version")     String version,
            @JsonProperty("path")        String path,
            @JsonProperty("tags")        List<String> tags,
            @JsonProperty("author")      String author,
            @JsonProperty("homepage")    String homepage,
            @JsonProperty("requires")    List<String> requires) {
        return new RegistryEntry(id, type, name, description, version, path, tags, author,
                homepage, requires);
    }
}
