package ai.mindconnect.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an operator decided about one tool — the difference from how it
 * arrives, nothing else.
 *
 * <p>Only deviations are stored. A tool without settings behaves exactly as
 * its source defines it, which is why a fresh installation needs no data at
 * all and a new tool module brings its tool along without a migration
 * (concept 22 §2).
 *
 * <p><b>Null means inherit, not empty.</b> A description set to {@code null}
 * is "whatever the source says"; an override that clears the text would
 * leave the model with a nameless capability, and nobody means that when
 * they empty a field.
 *
 * @param enabled                false hides the tool from catalogs and stops
 *                               it resolving; null inherits (enabled)
 * @param description            replaces the source's description; null inherits
 * @param parameterDescriptions  replaces single parameter descriptions by
 *                               parameter name; absent names inherit. The
 *                               schema's structure — types, required, enums —
 *                               is never touched: overriding it would build
 *                               calls the server rejects (§4.2)
 */
public record ToolSettings(
        Boolean enabled,
        String description,
        Map<String, String> parameterDescriptions
) {

    public ToolSettings {
        parameterDescriptions = parameterDescriptions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameterDescriptions));
    }

    public static ToolSettings none() {
        return new ToolSettings(null, null, Map.of());
    }

    /** True when this record says nothing — then it need not be stored at all. */
    public boolean isEmpty() {
        return enabled == null
                && (description == null || description.isBlank())
                && parameterDescriptions.isEmpty();
    }

    /**
     * The same decisions minus the description — for the case where a more
     * specific description has already won. The parameter texts survive:
     * what describes a tool and what describes its {@code query} field are
     * separate statements, and one being settled does not settle the other.
     */
    public ToolSettings withoutDescription() {
        return description == null ? this : new ToolSettings(enabled, null, parameterDescriptions);
    }

    /** Enabled unless someone said otherwise. */
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
