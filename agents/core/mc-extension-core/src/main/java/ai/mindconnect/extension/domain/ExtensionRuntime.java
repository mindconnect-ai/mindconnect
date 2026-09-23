package ai.mindconnect.extension.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where an extension's code runs. A {@code jar} sits on the classpath of the
 * installation and is loaded with it; a {@code remote} extension is a server
 * the host talks to over a protocol. The manifest is the same for both — what
 * it may contribute is not: decorating ports, replacing beans and bringing
 * tables are things only code in the process can do.
 */
public enum ExtensionRuntime {
    JAR("jar"),
    REMOTE("remote");

    private final String wireName;

    ExtensionRuntime(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static ExtensionRuntime fromWire(String value) {
        for (ExtensionRuntime runtime : values()) {
            if (runtime.wireName.equalsIgnoreCase(value) || runtime.name().equalsIgnoreCase(value)) {
                return runtime;
            }
        }
        throw new IllegalArgumentException("Unknown extension runtime: " + value);
    }
}
