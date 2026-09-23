package ai.mindconnect.agent.runtime.usermemory;

import java.util.Locale;

/**
 * What a memory is about — the same four kinds Claude Code files its auto
 * memory under. The kind tells the model how to use an entry: a
 * {@link #FEEDBACK} entry is guidance to follow, a {@link #REFERENCE} one a
 * pointer to look something up.
 */
public enum MemoryType {
    /** Who the user is: role, expertise, preferences. */
    USER,
    /** How the user wants the work done — corrections and confirmed approaches, with the why. */
    FEEDBACK,
    /** Ongoing work, goals, deadlines, decisions. */
    PROJECT,
    /** Where to find things: systems, documents, people. */
    REFERENCE;

    /** The lower-case name the model reads and writes. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive; {@code null} or unknown throws, naming the kinds there are. */
    public static MemoryType parse(String value) {
        if (value != null) {
            for (MemoryType type : values()) {
                if (type.name().equalsIgnoreCase(value.strip())) return type;
            }
        }
        throw new IllegalArgumentException("unknown memory type '" + value
                + "' — expected user|feedback|project|reference");
    }
}
