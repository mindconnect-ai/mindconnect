package ai.mindconnect.llm;

import ai.mindconnect.llm.domain.LlmConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LlmConfigAliasTest {

    private static Map<String, LlmConfig> registry(LlmConfig... configs) {
        Map<String, LlmConfig> byName = new HashMap<>();
        for (LlmConfig c : configs) byName.put(c.name(), c);
        return byName;
    }

    @Test
    void nonAliasResolvesToItself() {
        var concrete = LlmConfig.claude("real", "claude-sonnet-4-6", "key");
        var byName = registry(concrete);
        assertThat(concrete.resolveAlias(byName::get)).isSameAs(concrete);
    }

    @Test
    void aliasResolvesToTarget() {
        var concrete = LlmConfig.claude("real", "claude-sonnet-4-6", "key");
        var alias = LlmConfig.alias("default", "real");
        var byName = registry(concrete, alias);

        var resolved = alias.resolveAlias(byName::get);
        assertThat(resolved.name()).isEqualTo("real");
        assertThat(resolved.isAlias()).isFalse();
    }

    @Test
    void aliasChainIsFollowedToConcreteConfig() {
        var concrete = LlmConfig.claude("real", "claude-sonnet-4-6", "key");
        var mid = LlmConfig.alias("mid", "real");
        var top = LlmConfig.alias("default", "mid");
        var byName = registry(concrete, mid, top);

        assertThat(top.resolveAlias(byName::get).name()).isEqualTo("real");
    }

    @Test
    void aliasFactorySetsFlags() {
        var alias = LlmConfig.alias("default", "real");
        assertThat(alias.isAlias()).isTrue();
        assertThat(alias.delegatesTo()).isEqualTo("real");
        assertThat(alias.provider()).isNull();
    }

    @Test
    void missingTargetThrows() {
        var alias = LlmConfig.alias("default", "does-not-exist");
        var byName = registry(alias);
        assertThatThrownBy(() -> alias.resolveAlias(byName::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void blankTargetThrows() {
        var alias = LlmConfig.alias("default", "  ");
        var byName = registry(alias);
        assertThatThrownBy(() -> alias.resolveAlias(byName::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delegatesTo");
    }

    @Test
    void circularChainThrows() {
        var a = LlmConfig.alias("a", "b");
        var b = LlmConfig.alias("b", "a");
        var byName = registry(a, b);
        assertThatThrownBy(() -> a.resolveAlias(byName::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Circular");
    }

    @Test
    void selfReferenceThrows() {
        var a = LlmConfig.alias("a", "a");
        var byName = registry(a);
        assertThatThrownBy(() -> a.resolveAlias(byName::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Circular");
    }
}
