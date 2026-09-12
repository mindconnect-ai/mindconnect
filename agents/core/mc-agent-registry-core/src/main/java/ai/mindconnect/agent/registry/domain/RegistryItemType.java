package ai.mindconnect.agent.registry.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * What a registry entry installs. The wire name is the hyphenated lower-case
 * form ({@code llm-config}), because a registry's index is written by hand and
 * {@code LLM_CONFIG} in JSON reads like a leaked Java constant.
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
        String normalised = text.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (RegistryItemType type : values()) {
            if (type.wireName.equals(normalised)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown registry entry type '" + text + "'");
    }
}
