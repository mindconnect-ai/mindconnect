package ai.mindconnect.common.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EnvVarResolverTest {

    @Test
    void returnsNullForNull() {
        assertThat(EnvVarResolver.resolve(null, Map.of())).isNull();
    }

    @Test
    void returnsValueUnchangedWhenNoPlaceholder() {
        assertThat(EnvVarResolver.resolve("plain-value", Map.of())).isEqualTo("plain-value");
    }

    @Test
    void expandsSinglePlaceholder() {
        var result = EnvVarResolver.resolve("${MY_KEY}", Map.of("MY_KEY", "secret"));
        assertThat(result).isEqualTo("secret");
    }

    @Test
    void expandsPlaceholderEmbeddedInString() {
        var result = EnvVarResolver.resolve("https://${HOST}/api", Map.of("HOST", "example.com"));
        assertThat(result).isEqualTo("https://example.com/api");
    }

    @Test
    void expandsMultiplePlaceholders() {
        var result = EnvVarResolver.resolve("${SCHEME}://${HOST}", Map.of("SCHEME", "https", "HOST", "api.example.com"));
        assertThat(result).isEqualTo("https://api.example.com");
    }

    @Test
    void usesFallbackWhenVarAbsent() {
        var result = EnvVarResolver.resolve("${MISSING:default-val}", Map.of());
        assertThat(result).isEqualTo("default-val");
    }

    @Test
    void prefersEnvVarOverFallback() {
        var result = EnvVarResolver.resolve("${MY_KEY:fallback}", Map.of("MY_KEY", "real-value"));
        assertThat(result).isEqualTo("real-value");
    }

    @Test
    void throwsWhenVarAbsentAndNoFallback() {
        assertThatThrownBy(() -> EnvVarResolver.resolve("${MISSING}", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING");
    }

    @Test
    void fallbackCanBeEmpty() {
        var result = EnvVarResolver.resolve("${MISSING:}", Map.of());
        assertThat(result).isEqualTo("");
    }
}
