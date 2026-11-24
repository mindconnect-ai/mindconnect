package ai.mindconnect.agent.starter.runtime.engine;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import ai.mindconnect.vectorstore.tools.IndexDefinition;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Stores the secrets a namespace keeps as ciphertext it hands through — an
 * LLM config's API key, an index's database password — in the current
 * encryption scheme where an earlier one wrote them. Once at start, in every
 * namespace: both are rewritten when their record is saved, but a config
 * nobody edits would keep its earlier ciphertext for good.
 *
 * <p>The secrets that are decrypted on read — users' and namespaces'
 * variables, connections, OAuth client secrets — are installation-wide and
 * are rewritten where their repositories are built.
 */
class StoredSecretRewrite {

    private static final Logger log = LoggerFactory.getLogger(StoredSecretRewrite.class);

    private final EncryptionHelper encryption;
    private final LlmConfigRepository llmConfigs;
    private final VectorStores vectorStores;
    private final ScopeSupplier scope;

    StoredSecretRewrite(EncryptionHelper encryption, LlmConfigRepository llmConfigs, VectorStores vectorStores,
                        ScopeSupplier scope) {
        this.encryption = encryption;
        this.llmConfigs = llmConfigs;
        this.vectorStores = vectorStores;
        this.scope = scope;
    }

    /** Rewrites in each of {@code namespaces}; a runtime fixed to one namespace works in that one. */
    void rewriteIn(Collection<Namespace> namespaces) {
        if (scope instanceof ThreadBoundScopeSupplier bound) {
            namespaces.forEach(namespace -> bound.runIn(Scope.of(namespace), () -> rewriteBound(namespace)));
        } else {
            rewriteBound(scope.namespace());
        }
    }

    private void rewriteBound(Namespace namespace) {
        try {
            int rewritten = rewriteLlmConfigs() + rewriteIndexes();
            if (rewritten > 0) {
                log.info("{} stored secret(s) in namespace '{}' rewritten from the earlier encryption scheme to AES-GCM",
                        rewritten, namespace.value());
            }
        } catch (RuntimeException e) {
            // A namespace whose store cannot be read must not keep the server from starting:
            // its earlier values are still read, and rewritten when they are saved.
            log.warn("Could not rewrite the stored secrets of namespace '{}' in the current encryption scheme",
                    namespace.value(), e);
        }
    }

    private int rewriteLlmConfigs() {
        int rewritten = 0;
        for (LlmConfig config : llmConfigs.findAll()) {
            String apiKey = encryption.reencryptEarlierScheme(config.apiKey());
            if (apiKey != null && !apiKey.equals(config.apiKey())) {
                llmConfigs.save(config.withApiKey(apiKey));
                rewritten++;
            }
        }
        return rewritten;
    }

    private int rewriteIndexes() {
        int rewritten = 0;
        for (IndexDefinition index : vectorStores.registry().indexes()) {
            String password = encryption.reencryptEarlierScheme(index.password());
            if (password != null && !password.equals(index.password())) {
                vectorStores.saveIndex(index.withPassword(password));
                rewritten++;
            }
        }
        return rewritten;
    }
}
