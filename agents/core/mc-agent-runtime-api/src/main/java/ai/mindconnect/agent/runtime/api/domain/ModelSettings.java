package ai.mindconnect.agent.runtime.api.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which model is asked, and how. Every field may be missing: settings come
 * in layers — the request, the session, the agent, and whatever a
 * {@code ModelSettingsResolver} adds below them — and only the layers laid
 * over each other ({@link #over}) say what one model call uses.
 *
 * <p>Nothing here names a provider, a key or an endpoint. Those belong to
 * the installation, not to an agent; the {@code ModelGateway} maps
 * {@link #model} to them.
 *
 * @param model           the name of a model configuration, not a provider's
 *                        model id — an agent stays free of the provider
 * @param reasoningEffort how much the model may think, in the provider's
 *                        words ({@code low}, {@code medium}, {@code high})
 * @param extra           what only one provider knows
 */
public record ModelSettings(
        String model,
        Double temperature,
        Integer maxOutputTokens,
        String reasoningEffort,
        Map<String, Object> extra
) {

    /** The layer that sets nothing. */
    public static final ModelSettings NONE = new ModelSettings(null, null, null, null, Map.of());

    public ModelSettings {
        extra = Map.copyOf(extra);
    }

    public static ModelSettings ofModel(String model) {
        return new ModelSettings(model, null, null, null, Map.of());
    }

    /** These settings over a weaker layer: what is missing here comes from below. */
    public ModelSettings over(ModelSettings below) {
        Map<String, Object> merged = new LinkedHashMap<>(below.extra);
        merged.putAll(extra);
        return new ModelSettings(
                model != null ? model : below.model,
                temperature != null ? temperature : below.temperature,
                maxOutputTokens != null ? maxOutputTokens : below.maxOutputTokens,
                reasoningEffort != null ? reasoningEffort : below.reasoningEffort,
                merged);
    }
}
