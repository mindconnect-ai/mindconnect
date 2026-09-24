package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

/**
 * What the composer's model button says: the model the chat really runs on,
 * not the name of the config that points at it. {@code agent-default} tells
 * nobody anything; {@code gpt-5.1} does. An alias is followed to the config
 * it delegates to, and that config's model is shown.
 *
 * <p>Falls back to the config's own name when there is nothing better to
 * say — no repository, no such config, a broken or circular alias, or a
 * config without a model.
 */
public final class ModelLabel {

    private ModelLabel() {
    }

    public static String of(LlmConfigRepository configs, String configName) {
        if (configs == null || configName == null || configName.isBlank()) return configName;
        try {
            return configs.findResolvedByName(configName)
                    .map(LlmConfig::model)
                    .filter(model -> model != null && !model.isBlank())
                    .orElse(configName);
        } catch (IllegalStateException brokenAlias) {
            return configName;
        }
    }
}
