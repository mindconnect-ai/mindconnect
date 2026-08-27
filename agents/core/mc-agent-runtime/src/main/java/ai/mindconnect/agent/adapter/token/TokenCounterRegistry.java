package ai.mindconnect.agent.adapter.token;

import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.port.out.TokenCounters;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a TokenCounter for a given model name.
 *
 * Entries are checked in order — first match wins.
 * Custom entries can be prepended via {@link #register(String, TokenCounter)}
 * to override defaults.
 *
 * Default mappings:
 *   model contains "gpt-4o"            → jtokkit O200K_BASE
 *   model contains "gpt", "openai"     → jtokkit CL100K_BASE
 *   model contains "lm-studio", "lm_studio" → jtokkit CL100K_BASE (OpenAI-compat)
 *   anything else                      → char-based fallback
 */
public class TokenCounterRegistry implements TokenCounters {

    private static final Logger log = LoggerFactory.getLogger(TokenCounterRegistry.class);

    private record Entry(String pattern, TokenCounter counter) {}

    private final List<Entry> entries = new ArrayList<>();
    private final TokenCounter fallback = new CharBasedTokenCounter();

    // eagerly built shared instances (jtokkit registry init is not cheap)
    private static final TokenCounter CL100K = new JtokkitTokenCounter(EncodingType.CL100K_BASE);
    private static final TokenCounter O200K  = new JtokkitTokenCounter(EncodingType.O200K_BASE);

    public TokenCounterRegistry() {
        // defaults — order matters, more specific first
        entries.add(new Entry("gpt-4o",     O200K));
        entries.add(new Entry("o1",         O200K));
        entries.add(new Entry("o3",         O200K));
        entries.add(new Entry("gpt",        CL100K));
        entries.add(new Entry("openai",     CL100K));
        entries.add(new Entry("lm-studio",  CL100K));
        entries.add(new Entry("lm_studio",  CL100K));
        entries.add(new Entry("lmstudio",   CL100K));
    }

    /**
     * Prepend a custom entry so it takes priority over defaults.
     * Pattern is matched case-insensitively as a substring of the model name.
     */
    @Override
    public void register(String modelPattern, TokenCounter counter) {
        entries.add(0, new Entry(modelPattern.toLowerCase(), counter));
    }

    @Override
    public TokenCounter fallback() {
        return fallback;
    }

    @Override
    public TokenCounter forModel(String modelName) {
        if (modelName == null || modelName.isBlank()) return fallback;
        String lower = modelName.toLowerCase();
        for (Entry e : entries) {
            if (lower.contains(e.pattern())) {
                log.debug("TokenCounter for model '{}': {} (pattern='{}')",
                        modelName, e.counter().getClass().getSimpleName(), e.pattern());
                return e.counter();
            }
        }
        log.debug("TokenCounter for model '{}': CharBased (no pattern matched)", modelName);
        return fallback;
    }
}
