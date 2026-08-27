package ai.mindconnect.llm.domain;

import java.util.List;

/**
 * Describes one entry a provider understands in {@link LlmConfig#additionalParams()}
 * — the otherwise generic map gets a self-documenting catalog. The admin UI
 * renders exactly these specs as form fields for the selected provider (and
 * config type), and the REST API exposes them so external clients know what
 * the map accepts.
 *
 * @param key       the map key, e.g. {@code thinking}
 * @param label     human-readable field label
 * @param kind      how the value is entered/typed
 * @param options   allowed values for {@link Kind#SELECT}; empty otherwise.
 *                  A blank submission always means "omit the key".
 * @param hint      what the parameter does, shown as the field hint
 * @param appliesTo the config type the parameter applies to; {@code null} = both
 */
public record AdditionalParamSpec(
        String key,
        String label,
        Kind kind,
        List<String> options,
        String hint,
        LlmConfigType appliesTo
) {

    public enum Kind { TEXT, NUMBER, BOOLEAN, SELECT }

    public static AdditionalParamSpec select(String key, String label, List<String> options,
                                             String hint, LlmConfigType appliesTo) {
        return new AdditionalParamSpec(key, label, Kind.SELECT, options, hint, appliesTo);
    }

    public static AdditionalParamSpec text(String key, String label, String hint,
                                           LlmConfigType appliesTo) {
        return new AdditionalParamSpec(key, label, Kind.TEXT, List.of(), hint, appliesTo);
    }

    public static AdditionalParamSpec number(String key, String label, String hint,
                                             LlmConfigType appliesTo) {
        return new AdditionalParamSpec(key, label, Kind.NUMBER, List.of(), hint, appliesTo);
    }

    /** Does this parameter apply to a config of the given type? */
    public boolean appliesTo(LlmConfigType type) {
        return appliesTo == null || appliesTo == type;
    }
}
