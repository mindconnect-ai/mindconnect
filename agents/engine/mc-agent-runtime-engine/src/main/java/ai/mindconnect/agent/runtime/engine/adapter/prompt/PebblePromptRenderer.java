package ai.mindconnect.agent.runtime.engine.adapter.prompt;

import java.io.StringWriter;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.runtime.api.port.out.PromptRenderer;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.StringLoader;

/**
 * Instructions as Pebble templates: {@code {{ user }}}, conditions, loops.
 * The renderer the engine uses unless another is provided.
 *
 * <p>A name nobody provided renders as nothing, so instructions without any
 * template syntax come out as written. A template that cannot be rendered
 * comes out as written too: a turn with unrendered instructions is better
 * than no turn.
 */
public final class PebblePromptRenderer implements PromptRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(PebblePromptRenderer.class);

    private final PebbleEngine engine = new PebbleEngine.Builder()
            // The template is the string itself, not the name of a file.
            .loader(new StringLoader())
            .strictVariables(false)
            // Plain text for a model, not HTML for a browser.
            .autoEscaping(false)
            .build();

    @Override
    public String render(String template, Map<String, Object> variables) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        try {
            StringWriter rendered = new StringWriter(template.length());
            engine.getTemplate(template).evaluate(rendered, variables);
            return rendered.toString();
        } catch (Exception e) {
            LOG.warn("Instructions could not be rendered and are used as written: {}", e.getMessage());
            return template;
        }
    }
}
