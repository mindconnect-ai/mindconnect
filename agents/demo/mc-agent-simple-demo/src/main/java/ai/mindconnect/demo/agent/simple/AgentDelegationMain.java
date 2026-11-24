package ai.mindconnect.demo.agent.simple;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.sdk.AgentRuntime;

/**
 * One agent delegating to another, embedded: an orchestrator that names a
 * {@code web-researcher} as its sub-agent — which is what gives it the
 * {@code run_agent} tool — hands research off to it; the researcher has the
 * web tools ({@code web_read}; plus {@code web_search} when a Tavily API key
 * is found — via {@link McEnv} from the repository's {@code mc.env} or the
 * process environment). Both agents are defined in code — no JSON, no Spring.
 *
 * <p>The web tools come from the {@code mc-agent-tools-web} module on the
 * classpath, through the runtime's tool registry.
 */
public class AgentDelegationMain {

    public static void main(String[] args) {
        // From the repo's mc.env (or the process environment) — see McEnv.
        String tavilyKey = McEnv.get("TAVILY_API_KEY", "");
        System.out.println(tavilyKey.isBlank()
                ? "No TAVILY_API_KEY found - researcher gets web_read only."
                : "Tavily key found - researcher gets web_search + web_read.");

        // Sub-agent: fetches and reads web pages.
        AgentDefinition researcher = new AgentDefinition("web-researcher",
                "Reads web pages and reports what they say.",
                "You are a web researcher. For open questions, use web_search first, "
                        + "then web_read on the most promising result. Be brief and cite "
                        + "the URLs you used.",
                ModelSettings.ofModel("demo-llm"),
                tavilyKey.isBlank() ? ToolBinding.allOf("web_read") : ToolBinding.allOf("web_read", "web_search"),
                10, Map.of());

        // Orchestrator: no web access of its own — it must delegate. Naming
        // the researcher as its sub-agent is the whole grant.
        AgentDefinition orchestrator = new AgentDefinition("orchestrator",
                "Coordinates work by delegating to specialist agents.",
                "You coordinate specialist agents. You have NO web access yourself — "
                        + "for anything on the web, call the run_agent tool with "
                        + "name \"web-researcher\" and a precise, self-contained task. "
                        + "Then summarize the researcher's findings for the user.",
                ModelSettings.ofModel("demo-llm"), List.of(), 10,
                Map.of(SubAgentPlugin.SUB_AGENTS, List.of("web-researcher")));

        // tavilyApiKey: a plain setting the web_search tool reads.
        try (AgentRuntime runtime = DemoRuntime.build(Map.of("tavilyApiKey", tavilyKey))) {
            DemoRuntime.llmConfigFromClasspath(runtime, "demo-llm-config.json");
            DemoRuntime.agent(runtime, researcher);
            DemoRuntime.agent(runtime, orchestrator);

            String question = args.length > 0 ? String.join(" ", args)
                    : "What is the latest stable OpenJDK release and when was it published? "
                            + "Search the web for current information.";
            System.out.println("Q: " + question);

            String answer = DemoRuntime.ask(runtime, "orchestrator", "demo-user", question,
                    AgentDelegationMain::printEvent);
            System.out.println("A: " + answer);
        }
    }

    /** Makes the delegation visible: which agent runs, which tools it calls. */
    private static void printEvent(SessionEvent event) {
        if (event instanceof SessionEvent.TurnStarted started) {
            System.out.println("  [" + started.agentName() + "] turn started");
        } else if (event instanceof SessionEvent.ToolCallStarted call) {
            System.out.println("  tool call: " + call.toolName());
        }
    }
}
