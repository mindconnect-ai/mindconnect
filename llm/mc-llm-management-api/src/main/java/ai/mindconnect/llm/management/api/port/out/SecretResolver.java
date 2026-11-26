package ai.mindconnect.llm.management.api.port.out;

import ai.mindconnect.llm.management.api.domain.LlmConfig;

/**
 * Turns a config as it is stored into the config a provider receives: its
 * placeholders expanded and its key decrypted. The store keeps the raw form, so
 * a rotated secret or a changed variable takes effect without saving the config
 * again; the gateway resolves on every call, right before it hands the config
 * to a provider.
 */
public interface SecretResolver {

    LlmConfig resolve(LlmConfig config);
}
