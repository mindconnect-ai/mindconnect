package ai.mindconnect.demo.agent.simple;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;

/**
 * The smallest agent runtime there is, embedded in a plain Java program — no
 * Spring, no tools. {@link AgentRuntimeBuilder#of} gives the core: the turn
 * loop with the {@code CoreFeature} (LLM layer, conversations, agents). We
 * load one LLM config and one agent definition from the classpath, ask a
 * question, and print the answer. Everything else — tools, skills, workflows,
 * file upload — is a feature installed on top; see the other demos.
 *
 * <p>Needs a running OpenAI-compatible endpoint matching
 * {@code demo-llm-config.json} (defaults to LM Studio on
 * {@code http://localhost:1234} — adjust model/baseUrl there).
 */
public class UsingAgentRuntimeMain {

    public static void main(String[] args) {
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory())
                .llmConfigFromClasspath("demo-llm-config.json")     // configures the CoreFeature
                .agentDefinitionFromClasspath("demo-agent.json")
                .build()) {

            System.out.println("Features: " + runtime.features().all().stream().map(RuntimeFeature::name).toList());

            String question = args.length > 0 ? String.join(" ", args)
                    : "In one sentence: what is a vector store?";
            System.out.println("Q: " + question);
            String answer = runtime.ask("demo-agent", UserId.of("demo-user"), question, event -> { });
            System.out.println("A: " + answer);
        }
    }
}
