package ai.mindconnect.agent.registry.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * What a registry entry installs. The wire name is the hyphenated lower-case
 * form ({@code llm-config}), because a registry's index is written by hand and
 * {@code LLM_CONFIG} in JSON reads like a leaked Java constant.
 *
 * <p>{@link #SKILL} is a {@code SKILL.md} rather than a JSON file: front matter
 * with name, description and tools, then the instructions as Markdown.
 *
 * <p>{@link #PACKAGE} is the odd one out: it installs nothing itself. Its file
 * is a manifest of other entries — "an assistant, the two sub-agents it calls,
 * their workflow and the LLM alias they all point at" — and the import service
 * walks it.
 */
public enum RegistryItemType {

    LLM_CONFIG("llm-config", "LLM config"),
    AGENT("agent", "Agent"),
    WORKFLOW("workflow", "Workflow"),
    SKILL("skill", "Skill"),
    PACKAGE("package", "Package");

    private final String wireName;
    private final String label;

    RegistryItemType(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** For screens and reports. */
    public String label() {
        return label;
    }

    /**
     * Reads a type from an index. Tolerant about spelling — {@code llm-config},
     * {@code llm_config} and {@code LLM_CONFIG} all name the same thing, and an
     * index author should not have to guess which.
     *
     * @throws IllegalArgumentException when the text names no known type
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RegistryItemType fromWire(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Registry entry has no type");
        }
        RegistryItemType type = fromWireOrNull(text);
        if (type == null) {
            throw new IllegalArgumentException("Unknown registry entry type '" + text + "'");
        }
        return type;
    }

    /**
     * {@link #fromWire} for a reader that can live with not knowing: the type,
     * or {@code null} when the text names none this version has — the way an
     * index written for a newer Mindconnect is read by an older one.
     */
    public static RegistryItemType fromWireOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalised = text.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (RegistryItemType type : values()) {
            if (type.wireName.equals(normalised)) {
                return type;
            }
        }
        return null;
    }
}
