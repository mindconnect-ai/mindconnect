package ai.mindconnect.agent.runtime.engine.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;

/**
 * An agent's instructions as the model reads them: the template rendered
 * with what every {@link PromptVariableProvider} contributes. Asked anew
 * for every model call, so a value that changes mid-turn shows.
 */
final class InstructionRenderer {

    private final PromptRenderer renderer;
    private final Supplier<List<PromptVariableProvider>> providers;

    InstructionRenderer(PromptRenderer renderer, Supplier<List<PromptVariableProvider>> providers) {
        this.renderer = renderer;
        this.providers = providers;
    }

    String render(AgentContext context) {
        Map<String, Object> variables = new HashMap<>();
        // In the order of installation: a later provider replaces what an earlier one set.
        providers.get().forEach(provider -> variables.putAll(provider.variables(context.agent(), context.session())));
        return renderer.render(context.agent().instructions(), variables);
    }
}
