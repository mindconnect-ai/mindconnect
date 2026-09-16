package ai.mindconnect.user.adapter.env;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A user's variables are read for the user behind the current scope, kept
 * encrypted at rest and plain in memory; nobody's work sees nobody's variables.
 */
class UserEnvVarResolverTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final EncryptionHelper ENCRYPTION = new EncryptionHelper("0123456789abcdef");

    private final InMemoryUserRepository store = new InMemoryUserRepository();
    private final UserRepository users = new EncryptingUserRepository(store, ENCRYPTION);

    private static User alice(Map<String, String> environment) {
        return new User(ALICE, null, null, "Alice", null, NOW, NOW, null, environment);
    }

    @Test
    void valuesAreEncryptedAtRestAndPlainForTheUserBehindTheScope() {
        users.save(alice(Map.of("OPENAI_API_KEY", "sk-alice", "ODD", "enc:looks-like-a-tag")));

        Map<String, String> stored = store.findById(ALICE).orElseThrow().environment();
        assertThat(stored.get("OPENAI_API_KEY")).startsWith(EncryptionHelper.ENC).doesNotContain("sk-alice");
        assertThat(stored.get("ODD")).startsWith(EncryptionHelper.ENC).isNotEqualTo("enc:looks-like-a-tag");
        assertThat(users.findById(ALICE)).get().extracting(User::environment)
                .isEqualTo(Map.of("OPENAI_API_KEY", "sk-alice", "ODD", "enc:looks-like-a-tag"));
        assertThat(users.findAll()).singleElement().extracting(User::environment)
                .isEqualTo(Map.of("OPENAI_API_KEY", "sk-alice", "ODD", "enc:looks-like-a-tag"));

        var vars = new UserEnvVarResolver(users, ScopeSupplier.fixed(Scope.of(Namespace.DEFAULT, ALICE)));
        assertThat(vars.get("OPENAI_API_KEY")).contains("sk-alice");
        assertThat(vars.get("TAVILY_API_KEY")).isEmpty();
        assertThat(vars.get(null)).isEmpty();
        assertThat(vars.personal()).isTrue();
        assertThat(vars.shared().get("OPENAI_API_KEY")).as("personal values never reach the shared view").isEmpty();
    }

    @Test
    void aValueTheKeyNoLongerDecryptsIsLeftOutInsteadOfFailingTheRecord() {
        store.save(alice(Map.of("GOOD", ENCRYPTION.encryptTagged("v"), "STALE", new EncryptionHelper("fedcba9876543210").encryptTagged("w"))));

        assertThat(users.findById(ALICE)).get().extracting(User::environment).isEqualTo(Map.of("GOOD", "v"));
    }

    @Test
    void workOnNobodysBehalfAndAnUnknownUserGetNothing() {
        users.save(alice(Map.of("KEY", "v")));

        assertThat(new UserEnvVarResolver(users, ScopeSupplier.fixed(Namespace.DEFAULT)).get("KEY")).isEmpty();
        assertThat(new UserEnvVarResolver(users, ScopeSupplier.fixed(Scope.of(Namespace.DEFAULT, UserId.of("bob")))).asMap()).isEmpty();
    }

    @Test
    void aUserWithoutVariablesPassesThroughUntouched() {
        User plain = alice(null);
        users.save(plain);

        assertThat(store.findById(ALICE)).contains(plain);
        assertThat(users.findById(ALICE)).contains(plain);
    }
}
