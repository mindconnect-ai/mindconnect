package ai.mindconnect.agent.adapter.prompt;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.PromptContextProvider;
import ai.mindconnect.agent.port.out.PromptRenderer;
import ai.mindconnect.common.AuthenticationInfo;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.StringLoader;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link PromptRenderer} implementation using the Pebble templating engine.
 * <p>
 * The engine is configured with a {@link StringLoader} so the input template is
 * rendered verbatim — no file lookups, no class-path resolution, no surprises.
 * Strict variables are off so unbound variables silently render as empty,
 * keeping plain-text prompts (no templating syntax) as no-ops.
 */
public class PebblePromptRenderer implements PromptRenderer {

    private static final Logger log = LoggerFactory.getLogger(PebblePromptRenderer.class);

    private final PebbleEngine engine;
    private final List<PromptContextProvider> providers;

    public PebblePromptRenderer(List<PromptContextProvider> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(PromptContextProvider::priority))
                .toList();
        this.engine = new PebbleEngine.Builder()
                .loader(new StringLoader())
                .strictVariables(false)
                .autoEscaping(false)  // we render plain text, not HTML
                .cacheActive(true)    // cache parsed templates per template-string
                .build();
        log.info("PebblePromptRenderer initialised with {} provider(s): {}",
                this.providers.size(),
                this.providers.stream().map(p -> p.getClass().getSimpleName()).toList());
    }

    @Override
    public String render(String template, AgentDefinition def, AgentSession session,
                          AuthenticationInfo auth, Map<String, Object> extraVars) {
        if (template == null || template.isEmpty()) return template;

        Map<String, Object> ctx = new HashMap<>();
        for (PromptContextProvider provider : providers) {
            try {
                provider.contribute(ctx, def, session, auth);
            } catch (Exception e) {
                log.warn("PromptContextProvider {} failed: {}",
                        provider.getClass().getSimpleName(), e.getMessage());
            }
        }
        // Caller-provided variables win over provider-supplied ones.
        if (extraVars != null && !extraVars.isEmpty()) {
            ctx.putAll(extraVars);
        }

        try {
            PebbleTemplate compiled = engine.getTemplate(template);
            StringWriter writer = new StringWriter(template.length() + 256);
            compiled.evaluate(writer, ctx);
            return writer.toString();
        } catch (Exception e) {
            // Never crash a chat call because of a bad template — log and fall back
            // to the raw template so the agent still gets a system prompt.
            String agentLabel = def != null ? def.id() + " (" + def.name() + ")" : "<stateless>";
            log.warn("Prompt rendering failed for agent {}: {}", agentLabel, e.getMessage());
            return template;
        }
    }
}
