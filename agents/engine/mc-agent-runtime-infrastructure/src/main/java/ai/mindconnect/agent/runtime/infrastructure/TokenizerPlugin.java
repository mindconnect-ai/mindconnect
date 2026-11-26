package ai.mindconnect.agent.runtime.infrastructure;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Counts tokens with a real tokenizer instead of estimating them from a
 * text's length. Installed after the capabilities, its counter is the one
 * memory and whoever shows a context's size ask.
 *
 * <p>One encoding for every model — the one the current OpenAI models use.
 * The counter is asked with a text and not with a model, so it cannot choose
 * by model; for a model of another family the count is a close estimate, and
 * closer than a length divided by a constant.
 */
public final class TokenizerPlugin implements Plugin {

    @Override
    public String name() {
        return "tokenizer";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(TokenCounter.class, () -> {
            // Built once, on first use: loading an encoding's vocabulary is not cheap.
            Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
            return text -> text == null || text.isEmpty() ? 0 : encoding.countTokens(text);
        });
    }
}
