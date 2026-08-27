package ai.mindconnect.demo.agent.simple;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;

/**
 * Shows how little it takes to embed the agent runtime in a plain Java
 * program — no Spring. {@link AgentRuntimeBuilder} wires the whole graph
 * (file persistence, message store, LLM gateways, tool registry, turn
 * pipeline); we load one LLM config and one agent definition from the
 * classpath, ask a question, and print the answer.
 *
 * <p>Needs a running OpenAI-compatible endpoint matching
 * {@code demo-llm-config.json} (defaults to LM Studio on
 * {@code http://localhost:1234} — adjust model/baseUrl there).
 */
public class UsingAgentRuntimeMain {

    public static void main(String[] args) {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfigFromClasspath("demo-llm-config.json")
                .agentDefinitionFromClasspath("demo-agent.json")
                .build()) {

            String question = args.length > 0 ? String.join(" ", args)
                    : "In one sentence: what is a vector store?";
            System.out.println("Q: " + question);
            String answer = runtime.ask("demo-agent", "demo-user", question, event -> { });
            System.out.println("A: " + answer);
        }
    }
}
