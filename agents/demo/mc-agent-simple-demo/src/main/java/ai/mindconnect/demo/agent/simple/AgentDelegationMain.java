package ai.mindconnect.demo.agent.simple;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.common.Namespace;

import java.util.List;

/**
 * One agent delegating to another, embedded: an orchestrator with the inline
 * {@code run_agent} tool hands research off to a {@code web-researcher}
 * sub-agent equipped with the web tools ({@code web_read}; plus
 * {@code web_search} when a Tavily API key is found — via {@link McEnv} from
 * the repository's {@code mc.env} or the process environment). Both agents
 * are defined in code — no JSON, no Spring.
 */
public class AgentDelegationMain {

    public static void main(String[] args) {
        // From the repo's mc.env (or the process environment) — see McEnv.
        String tavilyKey = McEnv.get("TAVILY_API_KEY", "");
        System.out.println(tavilyKey.isBlank()
                ? "No TAVILY_API_KEY found - researcher gets web_read only."
                : "Tavily key found - researcher gets web_search + web_read.");

        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfigFromClasspath("demo-llm-config.json")
                .defaultLlmConfigName("demo-llm")
                .tavilyApiKey(tavilyKey)
                .build()) {

            Namespace ns = runtime.namespace();

            // Sub-agent: fetches and reads web pages.
            AgentDefinition researcher = AgentDefinition.create(ns, "web-researcher",
                    "Reads web pages and reports what they say.",
                    "You are a web researcher. For open questions, use web_search first, "
                            + "then web_read on the most promising result. Be brief and cite "
                            + "the URLs you used.",
                    null, "demo-llm");
            List<AgentTool> researcherTools = tavilyKey.isBlank()
                    ? List.of(AgentTool.of(researcher.id(), "web_read"))
                    : List.of(AgentTool.of(researcher.id(), "web_read"),
                              AgentTool.of(researcher.id(), "web_search"));
            runtime.agentDefinitions().save(researcher.withTools(researcherTools));

            // Orchestrator: no web access of its own — it must delegate.
            AgentDefinition orchestrator = AgentDefinition.create(ns, "orchestrator",
                    "Coordinates work by delegating to specialist agents.",
                    "You coordinate specialist agents. You have NO web access yourself — "
                            + "for anything on the web, call the run_agent tool with "
                            + "name \"web-researcher\" and a precise, self-contained task. "
                            + "Then summarize the researcher's findings for the user.",
                    null, "demo-llm");
            runtime.agentDefinitions().save(orchestrator.withTools(
                    List.of(AgentTool.of(orchestrator.id(), "run_agent"))));

            String question = args.length > 0 ? String.join(" ", args)
                    : "What is the latest stable OpenJDK release and when was it published? "
                            + "Search the web for current information.";
            System.out.println("Q: " + question);
            String answer = runtime.ask("orchestrator", "demo-user", question,
                    AgentDelegationMain::printEvent);
            System.out.println("A: " + answer);
        }
    }

    /** Makes the delegation visible: which agent runs, which tools it calls. */
    private static void printEvent(StreamEvent event) {
        if (event instanceof StreamEvent.SubAgentEvent sub) {
            if (sub.inner() instanceof StreamEvent.Token) {
                return;   // sub-agent text streams token by token — too noisy here
            }
            System.out.println("  [web-researcher] " + sub.inner().getClass().getSimpleName());
        } else if (!(event instanceof StreamEvent.Token)) {
            System.out.println("  [orchestrator] " + event.getClass().getSimpleName());
        }
    }
}
