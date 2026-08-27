package ai.mindconnect.agent.protocol.openai;

import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live example: sub-agents on the OpenAI backend via agent-as-tool
 * (concept 8, option a). OpenAI has NO native sub-agent notion — the adapter
 * injects a {@code run_agent} function tool and runs the delegation itself.
 * The protocol result is identical to the runtime backend: an
 * {@code AgentCall} item whose {@code childResponseId} points at a real,
 * individually addressable child response.
 */
@EnabledIf("openAiEnabled")
class OpenAiSubAgentsExampleTest {

    static boolean openAiEnabled() {
        return TestOpenAi.enabled();
    }

    private final OpenAiResponsesBackend backend =
            new OpenAiResponsesBackend(TestOpenAi.apiKey())
                    .register(PseudoAgent.of("poet", TestOpenAi.model(),
                            "You write exactly one two-line rhyme about the given topic."))
                    .register(PseudoAgent.of("lead", TestOpenAi.model(),
                            "You coordinate. For any poem request you MUST delegate to the "
                                    + "'poet' sub-agent via run_agent and return its poem verbatim.")
                            .withAgentTools("poet"));

    @Test
    void leadDelegatesToPoet() {
        Session session = backend.openSessionForAgent("examples", "lead");

        Response r = backend.create(ResponseRequest.text(session.id(),
                "I need a short poem about Lisbon."));

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).isNotBlank();

        // the delegation is visible as an AgentCall item …
        ConversationItem.AgentCall call = r.output().stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.AgentCall.class::isInstance)
                .map(ConversationItem.AgentCall.class::cast)
                .findFirst().orElseThrow();
        assertThat(call.agentName()).isEqualTo("poet");
        assertThat(call.childResponseId()).isNotBlank();

        // … its output pair is closed …
        assertThat(r.openFunctionCalls()).isEmpty();

        // … and the child is a REAL response, addressable one level deeper:
        Response child = backend.responses().get(call.childResponseId()).orElseThrow();
        assertThat(child.agentName()).isEqualTo("poet");
        assertThat(child.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(child.outputText()).isNotBlank();

        System.out.println("[lead]  " + r.outputText());
        System.out.println("[poet]  " + child.outputText());
    }
}
