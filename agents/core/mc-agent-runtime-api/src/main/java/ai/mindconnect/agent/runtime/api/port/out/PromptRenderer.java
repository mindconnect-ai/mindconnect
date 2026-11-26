package ai.mindconnect.agent.runtime.api.port.out;

import java.util.Map;

/**
 * Turns an agent's instructions, written as a template, into the text the
 * model reads. Which template language that is stays behind this port.
 */
public interface PromptRenderer {

    String render(String template, Map<String, Object> variables);
}
