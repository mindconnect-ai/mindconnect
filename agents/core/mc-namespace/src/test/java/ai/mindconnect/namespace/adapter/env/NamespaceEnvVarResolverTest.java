package ai.mindconnect.namespace.adapter.env;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** A namespace's variables are read for the namespace of the current scope, encrypted at rest and plain in memory. */
class NamespaceEnvVarResolverTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final EncryptionHelper ENCRYPTION = new EncryptionHelper("0123456789abcdef");

    private final InMemoryNamespaceRepository store = new InMemoryNamespaceRepository();
    private final NamespaceRepository namespaces = new EncryptingNamespaceRepository(store, ENCRYPTION);

    private static NamespaceDefinition acme(Map<String, String> environment) {
        return new NamespaceDefinition(ACME, "ACME", UserId.of("david"), Instant.parse("2026-09-15T12:00:00Z"),
                Set.of(UserId.of("alice")), environment);
    }

    @Test
    void valuesAreEncryptedAtRestAndPlainForTheNamespaceOfTheScope() {
        assertThat(namespaces.insert(acme(Map.of("OPENAI_API_KEY", "sk-acme")))).isTrue();

        assertThat(store.findById(ACME).orElseThrow().environment().get("OPENAI_API_KEY"))
                .startsWith(EncryptionHelper.ENC).doesNotContain("sk-acme");
        assertThat(namespaces.findById(ACME)).get().extracting(NamespaceDefinition::environment).isEqualTo(Map.of("OPENAI_API_KEY", "sk-acme"));
        assertThat(namespaces.findByMember(UserId.of("alice"))).singleElement().extracting(NamespaceDefinition::environment)
                .isEqualTo(Map.of("OPENAI_API_KEY", "sk-acme"));
        assertThat(namespaces.findAll()).singleElement().extracting(NamespaceDefinition::environment).isEqualTo(Map.of("OPENAI_API_KEY", "sk-acme"));

        var vars = new NamespaceEnvVarResolver(namespaces, ScopeSupplier.fixed(ACME));
        assertThat(vars.get("OPENAI_API_KEY")).contains("sk-acme");
        assertThat(vars.get("TAVILY_API_KEY")).isEmpty();
        assertThat(vars.personal()).isFalse();
        assertThat(vars.shared()).isSameAs(vars);
        assertThat(new NamespaceEnvVarResolver(namespaces, ScopeSupplier.fixed(Namespace.DEFAULT)).get("OPENAI_API_KEY"))
                .as("another namespace, or one without a record").isEmpty();
    }

    @Test
    void savingAgainReEncryptsThePlainValues_andANamespaceWithoutVariablesIsUntouched() {
        namespaces.save(acme(Map.of("KEY", "v1")));
        NamespaceDefinition loaded = namespaces.findById(ACME).orElseThrow();
        namespaces.save(loaded.withDisplayName("Acme Inc."));

        assertThat(namespaces.findById(ACME)).get().extracting(NamespaceDefinition::environment).isEqualTo(Map.of("KEY", "v1"));
        assertThat(store.findById(ACME).orElseThrow().environment().get("KEY")).startsWith(EncryptionHelper.ENC);

        NamespaceDefinition plain = acme(null);
        namespaces.save(plain);
        assertThat(store.findById(ACME)).contains(plain);
    }

    /** Renaming with a key that cannot read a stored value keeps that value instead of deleting it. */
    @Test
    void aValueTheKeyNoLongerDecryptsSurvivesARename() {
        String stale = new EncryptionHelper("fedcba9876543210").encryptTagged("w");
        store.save(acme(Map.of("STALE", stale, "GOOD", ENCRYPTION.encryptTagged("v"))));

        namespaces.save(namespaces.findById(ACME).orElseThrow().withDisplayName("Acme Inc."));

        assertThat(store.findById(ACME).orElseThrow().environment()).containsEntry("STALE", stale).containsKey("GOOD");
        assertThat(namespaces.findById(ACME)).get().extracting(NamespaceDefinition::environment).isEqualTo(Map.of("GOOD", "v"));
    }
}
