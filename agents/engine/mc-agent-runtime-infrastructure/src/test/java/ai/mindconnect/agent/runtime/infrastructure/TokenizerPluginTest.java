package ai.mindconnect.agent.runtime.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.capabilities.prompt.PromptPlugin;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;

/** A tokenizer counts what a model counts, and replaces the estimate the capabilities bring. */
class TokenizerPluginTest {

    @Test
    void installed_after_the_capabilities_it_is_the_counter_that_answers() {
        PluginRegistry registry = new DefaultPluginRegistry()
                .install(new PromptPlugin(Clock.systemUTC(), user -> ZoneOffset.UTC));
        int estimated = registry.get(TokenCounter.class).count("hello world");

        registry.install(new TokenizerPlugin());
        TokenCounter counter = registry.get(TokenCounter.class);

        assertThat(counter.count("hello world")).as("two words, two tokens").isEqualTo(2);
        assertThat(counter.count("")).isZero();
        assertThat(counter.count((String) null)).isZero();
        assertThat(counter.count("Donaudampfschifffahrtsgesellschaftskapitän"))
                .as("a long word is several tokens, not one").isGreaterThan(5);
        assertThat(estimated).as("the estimate it replaces goes by length").isNotEqualTo(2);
    }
}
