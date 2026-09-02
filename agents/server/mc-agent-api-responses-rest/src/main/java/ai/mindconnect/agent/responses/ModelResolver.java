package ai.mindconnect.agent.responses;

import ai.mindconnect.common.Namespace;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

/**
 * Turns the {@code model} field of an incoming request into the agent that
 * will answer it.
 *
 * <p>An OpenAI client has exactly one field to steer with, and callers mean
 * two different things by it. Someone porting from OpenAI writes a model —
 * {@code gpt-5-mini} — and wants that model to answer. Someone using
 * Mindconnect writes an agent — {@code web-researcher} — and wants its
 * prompt, tools and sub-agents. Both are served:
 *
 * <ol>
 *   <li>an <b>agent</b> of that name answers as itself;</li>
 *   <li>otherwise an <b>llm-config</b> of that name runs on the default
 *       agent, which keeps its tools and prompt but swaps the model;</li>
 *   <li>otherwise the request is rejected, naming both things that were
 *       looked for — a client that mistyped an agent should not silently
 *       get the default one.</li>
 * </ol>
 *
 * <p>The agent wins a name collision. It is the more specific object: it
 * already names an llm-config of its own, so reading it as a config would
 * throw away the rest of it.
 */
public final class ModelResolver {

    /** What {@code model} asked for, once resolved to something runnable. */
    public record Resolution(String agentName, String llmConfigOverride) {

        /** The agent runs as configured. */
        static Resolution agent(String agentName) {
            return new Resolution(agentName, null);
        }

        /** The default agent runs on a different model. */
        static Resolution onModel(String defaultAgent, String llmConfigName) {
            return new Resolution(defaultAgent, llmConfigName);
        }

        public boolean overridesModel() {
            return llmConfigOverride != null;
        }
    }

    private final AgentDefinitionRepository agents;
    private final LlmConfigRepository llmConfigs;
    private final Namespace namespace;
    private final String defaultAgentName;

    public ModelResolver(AgentDefinitionRepository agents, LlmConfigRepository llmConfigs,
                         Namespace namespace, String defaultAgentName) {
        this.agents = agents;
        this.llmConfigs = llmConfigs;
        this.namespace = namespace;
        this.defaultAgentName = defaultAgentName;
    }

    /**
     * @throws UnknownModelException when the name is neither an agent nor an
     *         llm-config
     */
    public Resolution resolve(String model) {
        if (model == null || model.isBlank()) {
            return Resolution.agent(defaultAgentName);
        }
        String name = model.trim();

        if (agents.findByName(namespace, name).isPresent()) {
            return Resolution.agent(name);
        }
        if (llmConfigs.findByName(name).isPresent()) {
            return Resolution.onModel(defaultAgentName, name);
        }
        throw new UnknownModelException(name);
    }

    /** Neither an agent nor an llm-config carries this name. */
    public static class UnknownModelException extends RuntimeException {
        private final String model;

        public UnknownModelException(String model) {
            super("The model '" + model + "' is neither an agent nor an llm-config on this server. "
                    + "Send an agent's name to have that agent answer, or an llm-config's name to run "
                    + "the default agent on that model.");
            this.model = model;
        }

        public String model() {
            return model;
        }
    }
}
