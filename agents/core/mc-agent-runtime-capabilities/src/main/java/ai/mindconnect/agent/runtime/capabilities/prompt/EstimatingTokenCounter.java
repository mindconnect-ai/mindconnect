package ai.mindconnect.agent.runtime.capabilities.prompt;

import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;

/**
 * Counts tokens by a rule of thumb, four characters to one. Wrong by a
 * margin for every model and good enough to decide when a history has to be
 * shortened; an assembly that needs the real number provides a tokenizer.
 */
public final class EstimatingTokenCounter implements TokenCounter {

    private static final int CHARACTERS_PER_TOKEN = 4;

    @Override
    public int count(String text) {
        return text == null ? 0 : (text.length() + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN;
    }
}
