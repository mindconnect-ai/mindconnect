package ai.mindconnect.common.env;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The sources a resolver can be built from, and how a chain combines them. */
class EnvVarResolverSourcesTest {

    /** A source that counts its reads and may be personal. */
    static class Counting implements EnvVarResolver {
        final Map<String, String> vars;
        final boolean personal;
        final AtomicInteger reads = new AtomicInteger();

        Counting(Map<String, String> vars, boolean personal) { this.vars = vars; this.personal = personal; }

        @Override public Optional<String> get(String name) { reads.incrementAndGet(); return Optional.ofNullable(vars.get(name)); }
        @Override public Map<String, String> asMap() { reads.incrementAndGet(); return vars; }
        @Override public boolean personal() { return personal; }
    }

    @Test
    void aChainAsksItsSourcesInOrderAndTheFirstOneThatKnowsTheNameAnswers() {
        EnvVarResolver user = EnvVarResolver.of(Map.of("OPENAI_API_KEY", "sk-alice"));
        EnvVarResolver namespace = EnvVarResolver.of(Map.of("OPENAI_API_KEY", "sk-acme", "TAVILY_API_KEY", "tvly-acme"));
        EnvVarResolver process = EnvVarResolver.of(Map.of("OPENAI_API_KEY", "sk-server", "HOME", "/srv"));

        EnvVarResolver chain = EnvVarResolver.chain(user, namespace, process);

        assertThat(chain.get("OPENAI_API_KEY")).contains("sk-alice");
        assertThat(chain.get("TAVILY_API_KEY")).contains("tvly-acme");
        assertThat(chain.get("HOME")).contains("/srv");
        assertThat(chain.get("NOPE")).isEmpty();
        assertThat(chain.asMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "OPENAI_API_KEY", "sk-alice", "TAVILY_API_KEY", "tvly-acme", "HOME", "/srv"));
    }

    @Test
    void personalSourcesAnswerSecretsOnly_theSharedViewLeavesThemOut() {
        Counting user = new Counting(Map.of("OPENAI_API_KEY", "sk-alice", "OPENAI_BASE_URL", "https://alice.example"), true);
        EnvVarResolver process = EnvVarResolver.of(Map.of("OPENAI_API_KEY", "sk-server", "OPENAI_BASE_URL", "https://api.openai.com"));
        EnvVarResolver chain = EnvVarResolver.chain(user, process);

        assertThat(chain.get("OPENAI_BASE_URL")).contains("https://alice.example");
        assertThat(chain.shared().get("OPENAI_BASE_URL")).contains("https://api.openai.com");
        assertThat(chain.shared().get("OPENAI_API_KEY")).contains("sk-server");
        assertThat(user.shared().get("OPENAI_API_KEY")).isEmpty();
        assertThat(process.shared()).isSameAs(process);
        assertThat(EnvVarResolver.chain(process).shared()).isInstanceOf(ChainedEnvVarResolver.class);
    }

    @Test
    void memoizedReadsEachNameOnceAndKeepsTheSharedViewMemoized() {
        Counting store = new Counting(Map.of("A", "1"), false);
        EnvVarResolver memo = EnvVarResolver.chain(store).memoized();

        assertThat(memo.get("A")).contains("1");
        assertThat(memo.get("A")).contains("1");
        assertThat(memo.get("B")).isEmpty();
        assertThat(memo.get("B")).isEmpty();
        assertThat(store.reads.get()).isEqualTo(2);
        assertThat(memo.memoized()).isSameAs(memo);
        assertThat(memo.shared()).isSameAs(memo);
        assertThat(memo.resolve("${A}-${A}")).isEqualTo("1-1");
        assertThat(store.reads.get()).isEqualTo(2);
    }

    @Test
    void aSourceThatThrowsStopsTheLookupInsteadOfFallingBack() {
        EnvVarResolver broken = new EnvVarResolver() {
            @Override public Optional<String> get(String name) { throw new IllegalStateException("store down"); }
            @Override public Map<String, String> asMap() { throw new IllegalStateException("store down"); }
        };
        EnvVarResolver chain = EnvVarResolver.chain(broken, EnvVarResolver.of(Map.of("X", "1")));

        assertThatThrownBy(() -> chain.get("X")).isInstanceOf(IllegalStateException.class).hasMessage("store down");
        assertThatThrownBy(() -> new ChainedEnvVarResolver(java.util.Arrays.asList(EnvVarResolver.none(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMapSourceTreatsANullValueAsAbsent() {
        Map<String, String> vars = new HashMap<>();
        vars.put("A", "1");
        vars.put("B", null);

        EnvVarResolver source = EnvVarResolver.of(vars);

        assertThat(source.get("A")).contains("1");
        assertThat(source.get("B")).isEmpty();
        assertThat(source.get(null)).isEmpty();
        assertThat(EnvVarResolver.none().get("A")).isEmpty();
        assertThat(EnvVarResolver.chain(List.of()).get("A")).isEmpty();
    }

    @Test
    void theSystemSourceReadsTheProcessEnvironment() {
        Map.Entry<String, String> any = System.getenv().entrySet().iterator().next();

        assertThat(EnvVarResolver.system().get(any.getKey())).isEqualTo(Optional.of(any.getValue()));
        assertThat(EnvVarResolver.system().get("DEFINITELY_NOT_SET_XYZ_123")).isEmpty();
        assertThat(EnvVarResolver.system().asMap()).isEqualTo(System.getenv());
    }

    @Test
    void aVariableNameIsLettersDigitsAndUnderscoresNotStartingWithADigit() {
        assertThat(EnvVarResolver.isValidName("OPENAI_API_KEY")).isTrue();
        assertThat(EnvVarResolver.isValidName("_x1")).isTrue();
        assertThat(EnvVarResolver.isValidName("1abc")).isFalse();
        assertThat(EnvVarResolver.isValidName("with space")).isFalse();
        assertThat(EnvVarResolver.isValidName(null)).isFalse();

        assertThatThrownBy(() -> EnvVarResolver.requireValid(Map.of("bad name", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bad name");
        assertThatThrownBy(() -> EnvVarResolver.requireValid("KEY", " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KEY");
        EnvVarResolver.requireValid(Map.of("KEY", "value"));
        EnvVarResolver.requireValid(null);
    }
}
