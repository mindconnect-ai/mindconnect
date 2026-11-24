package ai.mindconnect.agent.starter.runtime.engine;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import ai.mindconnect.vectorstore.tools.IndexDefinition;
import ai.mindconnect.vectorstore.tools.VectorStoreRegistry;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** At start, the ciphertexts a namespace hands through are stored in the current scheme — in every namespace. */
class StoredSecretRewriteTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final Namespace GLOBEX = new Namespace("globex");
    private static final EncryptionHelper ENCRYPTION = new EncryptionHelper("0123456789abcdef");

    /** A stored value as the scheme before AES-GCM wrote it: {@code enc:} and the Base64 of AES/ECB. */
    private static String writtenByTheEarlierScheme(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES"));
            return EncryptionHelper.ENC + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static VectorStores storesWith(IndexDefinition... indexes) {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        when(registry.indexes()).thenReturn(List.of(indexes));
        VectorStores stores = mock(VectorStores.class);
        when(stores.registry()).thenReturn(registry);
        return stores;
    }

    private static IndexDefinition index(String name, String password) {
        return new IndexDefinition(name, "pgvector", "kb", "jdbc:postgresql://db/kb", "kb", password, null, null);
    }

    @Test
    void an_api_key_of_the_earlier_scheme_is_rewritten_in_every_namespace() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();
        LlmConfigRepository configs = new InMemoryLlmConfigRepository(scope);
        String current = ENCRYPTION.encryptTagged("sk-current");
        scope.runIn(Scope.of(ACME), () -> {
            configs.save(LlmConfig.claude("earlier", "claude-sonnet-4-6", writtenByTheEarlierScheme("sk-acme")));
            configs.save(LlmConfig.claude("current", "claude-sonnet-4-6", current));
            configs.save(LlmConfig.claude("placeholder", "claude-sonnet-4-6", "${ANTHROPIC_API_KEY}"));
            return null;
        });
        scope.runIn(Scope.of(GLOBEX), () -> {
            configs.save(LlmConfig.claude("earlier", "claude-sonnet-4-6", writtenByTheEarlierScheme("sk-globex")));
            return null;
        });

        new StoredSecretRewrite(ENCRYPTION, configs, storesWith(), scope).rewriteIn(List.of(ACME, GLOBEX));

        scope.runIn(Scope.of(ACME), () -> {
            String rewritten = configs.findByName("earlier").orElseThrow().apiKey();
            assertThat(rewritten).startsWith("enc:v2:");
            assertThat(ENCRYPTION.resolve(rewritten)).isEqualTo("sk-acme");
            assertThat(configs.findByName("current").orElseThrow().apiKey()).isEqualTo(current);
            assertThat(configs.findByName("placeholder").orElseThrow().apiKey()).isEqualTo("${ANTHROPIC_API_KEY}");
        });
        scope.runIn(Scope.of(GLOBEX), () -> assertThat(ENCRYPTION.resolve(configs.findByName("earlier").orElseThrow().apiKey()))
                .isEqualTo("sk-globex"));
    }

    @Test
    void an_index_password_of_the_earlier_scheme_is_saved_in_the_current_one_and_no_other_index_is_saved() {
        IndexDefinition earlier = index("earlier", writtenByTheEarlierScheme("pw"));
        VectorStores stores = storesWith(earlier, index("current", ENCRYPTION.encryptTagged("pw")),
                index("placeholder", "${KB_PASSWORD}"), index("none", null));
        ScopeSupplier scope = ScopeSupplier.fixed(ACME);

        new StoredSecretRewrite(ENCRYPTION, new InMemoryLlmConfigRepository(scope), stores, scope).rewriteIn(List.of(GLOBEX));

        var saved = org.mockito.ArgumentCaptor.forClass(IndexDefinition.class);
        verify(stores).saveIndex(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("earlier");
        assertThat(saved.getValue().password()).startsWith("enc:v2:");
        assertThat(ENCRYPTION.resolve(saved.getValue().password())).isEqualTo("pw");
    }

    @Test
    void a_namespace_whose_store_cannot_be_read_does_not_stop_the_others() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();
        LlmConfigRepository configs = new InMemoryLlmConfigRepository(scope);
        scope.runIn(Scope.of(GLOBEX), () -> {
            configs.save(LlmConfig.claude("earlier", "claude-sonnet-4-6", writtenByTheEarlierScheme("sk-globex")));
            return null;
        });
        VectorStores stores = mock(VectorStores.class);
        VectorStoreRegistry empty = mock(VectorStoreRegistry.class);
        when(stores.registry()).thenThrow(new IllegalStateException("no database")).thenReturn(empty);

        new StoredSecretRewrite(ENCRYPTION, configs, stores, scope).rewriteIn(List.of(ACME, GLOBEX));

        scope.runIn(Scope.of(GLOBEX), () -> assertThat(configs.findByName("earlier").orElseThrow().apiKey()).startsWith("enc:v2:"));
        verify(stores, never()).saveIndex(any());
    }
}
