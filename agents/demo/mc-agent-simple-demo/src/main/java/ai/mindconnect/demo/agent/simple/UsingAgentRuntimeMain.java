package ai.mindconnect.demo.agent.simple;

import java.util.Map;

import ai.mindconnect.agent.sdk.AgentRuntime;

/**
 * The smallest agent runtime there is, embedded in a plain Java program — no
 * Spring, no tools. {@link DemoRuntime#build} assembles it from plugins, the
 * way a server does. We load one LLM config and one agent from the
 * classpath, ask a question, and print the answer. See
 * {@link AgentDelegationMain} for tools and sub-agents.
 *
 * <p>Needs a running OpenAI-compatible endpoint matching
 * {@code demo-llm-config.json} (defaults to LM Studio on
 * {@code http://localhost:1234} — adjust model/baseUrl there).
 */
public class UsingAgentRuntimeMain {

    public static void main(String[] args) {
        try (AgentRuntime runtime = DemoRuntime.build(Map.of())) {
            DemoRuntime.llmConfigFromClasspath(runtime, "demo-llm-config.json");
            DemoRuntime.agentFromClasspath(runtime, "demo-agent.json");

            String question = args.length > 0 ? String.join(" ", args)
                    : "In one sentence: what is a vector store?";
            System.out.println("Q: " + question);

            String answer = DemoRuntime.ask(runtime, "demo-agent", "demo-user", question, event -> { });
            System.out.println("A: " + answer);
        }
    }
}
