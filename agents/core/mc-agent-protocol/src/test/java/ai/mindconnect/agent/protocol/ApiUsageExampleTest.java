package ai.mindconnect.agent.protocol;

import ai.mindconnect.agent.protocol.api.AgentResponses;
import ai.mindconnect.agent.protocol.api.Conversations;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.Sessions;
import ai.mindconnect.agent.protocol.api.SubscribeRequest;
import ai.mindconnect.agent.protocol.api.Subscription;
import ai.mindconnect.agent.protocol.api.ToolDefinition;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API showcase — how a client uses the protocol, end to end. The scenarios
 * are {@link Disabled} because no implementation exists yet; the point is
 * that this file COMPILES against the protocol surface and reads as
 * documentation. The three interfaces would come from the runtime builder
 * (in-memory) or a remote client (HTTP/WS) — same types either way.
 */
@Disabled("API showcase — no implementation behind the interfaces yet")
class ApiUsageExampleTest {

    // In real use: AgentRuntime.builder()...build() or new HttpAgentClient(url)
    Sessions sessions;
    AgentResponses responses;
    Conversations conversations;

    @Test
    void simpleBlockingChat() {
        // The request is thin on purpose: prompt, model, server-side tools and
        // memory all come from the agent the session is bound to.
        Session session = sessions.open("local", "travel-assistant");

        Response response = responses.create(
                ResponseRequest.text(session.id(), "Find me a hotel in Lisbon for next weekend"));

        assertThat(response.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(response.outputText()).contains("Lisbon");

        // Server-side tool use is visible as item pairs in the output:
        response.output().stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.FunctionCall.class::isInstance)
                .map(ConversationItem.FunctionCall.class::cast)
                .forEach(call -> System.out.println("agent used tool: " + call.name()));
    }

    @Test
    void streamingWithLiveTokens() {
        Session session = sessions.open("acme", "travel-assistant");

        Response started = responses.create(
                ResponseRequest.text(session.id(), "Write a short Lisbon itinerary")
                        .inBackground());

        try (Subscription ignored = responses.subscribe(
                SubscribeRequest.replay(started.id()),          // afterSeq=0: items so far + live rest
                event -> {
                    switch (event) {
                        case ResponseEvent.OutputTextDelta d  -> System.out.print(d.delta());
                        case ResponseEvent.OutputItemDone done -> System.out.println("\n[item done: "
                                + done.entry().item().getClass().getSimpleName() + "]");
                        case ResponseEvent.Completed c         -> System.out.println("tokens: "
                                + c.usage().totalTokens());
                        default -> { }
                    }
                })) {
            // ... UI runs; closing the subscription detaches the consumer,
            // the response itself keeps running (it is background work).
        }
    }

    @Test
    void approvalFlow_turnEndsAndResumes() {
        Session session = sessions.open("local", "ops-agent");

        // The agent wants to call a tool that an advisor gates behind approval:
        // the response ENDS — no parked thread, state is just the conversation.
        Response paused = responses.create(
                ResponseRequest.text(session.id(), "Restart the staging cluster"));

        assertThat(paused.status()).isEqualTo(ResponseStatus.INCOMPLETE);
        assertThat(paused.incompleteReason()).isEqualTo(IncompleteReason.WAITING_FOR_APPROVAL);

        ConversationItem.ApprovalRequest request = lastItemOf(paused, ConversationItem.ApprovalRequest.class);
        System.out.println("needs approval: " + request.kind() + " " + request.payload());

        // Later — possibly days later, possibly from another device — the
        // human answers. The loop re-runs, the advisor sees the approval in
        // the transcript, the gated call proceeds.
        Response resumed = responses.create(
                ResponseRequest.approval(session.id(), request.requestId(), true));

        assertThat(resumed.status()).isEqualTo(ResponseStatus.COMPLETED);
    }

    @Test
    void clientSideTool_openAiStyle() {
        Session session = sessions.open("acme", "travel-assistant");

        // The client declares a function it executes ITSELF (OpenAI mechanic).
        ToolDefinition localLookup = new ToolDefinition(
                "loyalty_points",
                "Look up the user's hotel loyalty point balance",
                Map.of("type", "object", "properties", Map.of()));

        Response waiting = responses.create(
                ResponseRequest.text(session.id(), "Book the hotel with my points")
                        .withClientTools(List.of(localLookup)));

        // The runtime cannot run a client tool: same INCOMPLETE mechanic as approvals.
        assertThat(waiting.incompleteReason()).isEqualTo(IncompleteReason.WAITING_FOR_TOOL_OUTPUT);
        ConversationItem.FunctionCall open = lastItemOf(waiting, ConversationItem.FunctionCall.class);

        String result = "{\"points\": 12500}";               // executed locally
        Response done = responses.create(
                ResponseRequest.toolOutput(session.id(), open.callId(), result));

        assertThat(done.status()).isEqualTo(ResponseStatus.COMPLETED);
    }

    @Test
    void followSubAgents_flatStreamsRecursiveProtocol() {
        Session session = sessions.open("acme", "research-lead");

        Response root = responses.create(
                ResponseRequest.text(session.id(), "Compare vector databases").inBackground());

        // Option A: one aggregated subscription — child events multiplexed in,
        // each event still tagged with its own responseId.
        responses.subscribe(
                SubscribeRequest.replay(root.id()).withChildren(),
                event -> System.out.println(event.responseId() + " " + event.getClass().getSimpleName()));

        // Option B: follow one sub-agent individually — the protocol is
        // recursive, a child is just another response one level deeper.
        responses.get(root.id()).orElseThrow().output().stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.AgentCall.class::isInstance)
                .map(ConversationItem.AgentCall.class::cast)
                .findFirst()
                .ifPresent(call -> {
                    responses.subscribe(SubscribeRequest.live(call.childResponseId()),
                            e -> System.out.println("  [" + call.agentName() + "] " + e));
                    responses.cancel(call.childResponseId());   // kill one branch only —
                    // the parent gets an error tool result and re-plans
                });
    }

    @Test
    void conversationIsTheDurableTruth() {
        Session session = sessions.open("acme", "travel-assistant");
        responses.create(ResponseRequest.text(session.id(), "Hi!"));

        // Everything any response ever produced is in the conversation's log —
        // multimodal input included:
        conversations.append(session.conversationId(), new ConversationItem.Message(
                ai.mindconnect.agent.protocol.item.Role.USER,
                List.of(new ContentPart.Text("What is on this receipt?"),
                        new ContentPart.Image(
                                new ContentPart.MediaSource.FileId("file_receipt_1"),
                                ContentPart.Image.Detail.HIGH))));

        List<ConversationItemRecord> log = conversations.items(session.conversationId(), 0, 100);
        log.forEach(e -> System.out.println(e.seq() + "  " + e.item().getClass().getSimpleName()));

        // A different agent can continue the SAME history:
        Session reviewer = sessions.openOn(session.conversationId(), "expense-reviewer");
        responses.create(ResponseRequest.text(reviewer.id(), "Summarize the expenses above"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static <T extends ConversationItem> T lastItemOf(Response response, Class<T> type) {
        return response.output().stream()
                .map(ConversationItemRecord::item)
                .filter(type::isInstance)
                .map(type::cast)
                .reduce((a, b) -> b)
                .orElseThrow();
    }
}
