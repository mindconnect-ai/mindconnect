package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiLink;

/**
 * Read-only detail view of a single LLM configuration with Edit and
 * Delete actions in the header.
 */
public final class LlmConfigDetailComponent implements UiComponent {

    private final LlmConfig config;

    public LlmConfigDetailComponent(LlmConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "llm-config-detail-" + config.id();
    }

    @Override
    public UiDetail render() {
        UiDetail detail = UiDetail.of(id(), config.name())
                .field(UiField.text("name", "Name", config.name()))
                .field(UiField.bool("isAlias", "Is Alias", config.isAlias()))
                .field(UiField.text("delegatesTo", "Delegates To", config.delegatesTo()))
                .field(UiField.text("provider", "Provider",
                        config.provider() != null ? config.provider().name() : null))
                .field(UiField.text("type", "Type", config.isEmbedding() ? "Embedding" : "Chat"))
                .field(UiField.text("model", "Model", config.model()))
                .field(UiField.text("baseUrl", "Base URL", config.baseUrl()))
                .field(UiField.text("apiKey", "API Key", "••••••••"))
                .field(UiField.number("defaultTemperature", "Temperature", config.defaultTemperature()))
                .field(UiField.number("maxOutputTokens", "Max Output Tokens", config.maxOutputTokens()))
                .field(UiField.number("contextWindowTokens", "Context Window", config.contextWindowTokens()))
                .field(UiField.text("retry", "Retry on rate limit", retrySummary(config)))
                .field(UiField.text("maxConcurrentRequests", "Max Concurrent Requests",
                        config.rateLimit() == null ? "Unlimited"
                                : String.valueOf(config.rateLimit().maxConcurrentRequests())))
                .action(UiAction.primary("edit", "Edit").icon("edit")
                        .dispatch("GET", "/admin/api/llm-configs/" + config.id() + "/edit"))
                .action(UiAction.secondary("test", "Test").icon("flash")
                        .dispatch("GET", "/admin/api/llm-configs/" + config.id() + "/test"))
                .action(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete config '" + config.name() + "'?")
                        .dispatch("DELETE", "/admin/api/llm-configs/" + config.id()))
                .link(UiLink.of("back", "/admin/llm-configs", "← Back to LLM Configs"));
        // Provider parameters straight from the catalog — the detail view
        // shows exactly the fields the selected provider understands.
        if (config.provider() != null) {
            for (var spec : config.provider().additionalParams(config.type())) {
                detail.field(UiField.text(spec.key(), spec.label(),
                        paramOrDash(config, spec.key())));
            }
        }
        return detail;
    }

    /** Reads a provider param from additionalParams, or "—" when unset. */
    private static String paramOrDash(LlmConfig config, String key) {
        if (config.additionalParams() == null) return "—";
        Object v = config.additionalParams().get(key);
        return v == null || v.toString().isBlank() ? "—" : v.toString();
    }

    /** Human-readable one-liner for the retry policy, or "Off" when none is set. */
    private static String retrySummary(LlmConfig config) {
        var r = config.retry();
        if (r == null || !r.enabled()) return "Off";
        return r.maxAttempts() + " attempts, backoff " + r.baseBackoffMillis()
                + "–" + r.maxBackoffMillis() + " ms";
    }
}
