package ai.mindconnect.common.env;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EnvVarResolverTest {

    private static String resolve(String value, Map<String, String> env) {
        return EnvVarResolver.of(env).resolve(value);
    }

    @Test
    void resolvesAgainstAChainOfSources() {
        var chain = EnvVarResolver.chain(
                EnvVarResolver.of(Map.of("KEY", "mine")),
                EnvVarResolver.of(Map.of("KEY", "theirs", "URL", "http://x")));

        assertThat(chain.resolve("${KEY}@${URL}/${PATH_X:p}")).isEqualTo("mine@http://x/p");
    }

    @Test
    void returnsNullForNull() {
        assertThat(resolve(null, Map.of())).isNull();
    }

    @Test
    void returnsValueUnchangedWhenNoPlaceholder() {
        assertThat(resolve("plain-value", Map.of())).isEqualTo("plain-value");
    }

    @Test
    void expandsSinglePlaceholder() {
        var result = resolve("${MY_KEY}", Map.of("MY_KEY", "secret"));
        assertThat(result).isEqualTo("secret");
    }

    @Test
    void expandsPlaceholderEmbeddedInString() {
        var result = resolve("https://${HOST}/api", Map.of("HOST", "example.com"));
        assertThat(result).isEqualTo("https://example.com/api");
    }

    @Test
    void expandsMultiplePlaceholders() {
        var result = resolve("${SCHEME}://${HOST}", Map.of("SCHEME", "https", "HOST", "api.example.com"));
        assertThat(result).isEqualTo("https://api.example.com");
    }

    @Test
    void usesFallbackWhenVarAbsent() {
        var result = resolve("${MISSING:default-val}", Map.of());
        assertThat(result).isEqualTo("default-val");
    }

    @Test
    void prefersEnvVarOverFallback() {
        var result = resolve("${MY_KEY:fallback}", Map.of("MY_KEY", "real-value"));
        assertThat(result).isEqualTo("real-value");
    }

    @Test
    void throwsWhenVarAbsentAndNoFallback() {
        assertThatThrownBy(() -> resolve("${MISSING}", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING");
    }

    @Test
    void fallbackCanBeEmpty() {
        var result = resolve("${MISSING:}", Map.of());
        assertThat(result).isEqualTo("");
    }
}
