package ai.mindconnect.agent.migration.earlier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.format.AgentJson;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.migration.Migration;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.runtime.capabilities.memory.ConversationSummary;
import ai.mindconnect.agent.runtime.capabilities.memory.SessionSummaryStore;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.store.SessionMessageMigration;
import ai.mindconnect.agent.runtime.store.SessionMessageRepository;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.message.domain.ConversationId;

/**
 * Takes over what the earlier runtime kept: its agents become managed agents,
 * its sessions sessions of the runtime, each with the id it had, and a
 * session's conversation becomes its messages in its own repository, each
 * with its id, place and time. The conversation stays where it is and the
 * session names it, which is the way back to keeping messages as
 * conversations.
 *
 * <p>What a session knew is kept under the keys the runtime reads: title,
 * type, working directories, attachments, approved and activated tools, and
 * the conversation's latest summary. A session whose agent is gone keeps no
 * agent. An agent or session that is there already is left as it is.
 */
public final class EarlierRuntimeMigration implements Migration {

    private static final List<String> KEPT = List.of(ManagedSession.TITLE, ManagedSession.WORKING_DIRECTORY,
            ManagedSession.ADDITIONAL_DIRECTORIES, ManagedSession.ATTACHMENTS, "approvedTools", "activatedTools");

    private final EarlierRuntimeData earlier;
    private final AgentRepository agents;
    private final SessionRepository sessions;
    private final RepositorySessionStore conversations;
    private final SessionMessageRepository target;
    private final AgentJson agentJson;
    private final Clock clock;

    /**
     * @param conversations reads a conversation's messages as the runtime's
     * @param target        where a session's messages are kept from now on
     */
    public EarlierRuntimeMigration(EarlierRuntimeData earlier, AgentRepository agents, SessionRepository sessions,
                                   RepositorySessionStore conversations, SessionMessageRepository target,
                                   AgentJson agentJson, Clock clock) {
        this.earlier = earlier;
        this.agents = agents;
        this.sessions = sessions;
        this.conversations = conversations;
        this.target = target;
        this.agentJson = agentJson;
        this.clock = clock;
    }

    @Override
    public String id() {
        return "earlier-runtime";
    }

    @Override
    public String version() {
        return "0.8.5";
    }

    @Override
    public String migrate() {
        List<String> failed = new ArrayList<>();
        int[] agentCount = new int[2];
        for (String json : earlier.agents()) {
            try {
                ManagedAgent agent = agentJson.read(json);
                if (agents.find(agent.id()).isPresent()) {
                    agentCount[1]++;
                } else {
                    agents.create(agent);
                    agentCount[0]++;
                }
            } catch (RuntimeException e) {
                failed.add("agent: " + e.getMessage());
            }
        }
        int[] sessionCount = new int[3];
        for (Map<String, Object> old : earlier.sessions()) {
            try {
                sessionCount[take(old)]++;
            } catch (RuntimeException e) {
                failed.add("session " + old.get("id") + ": " + e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("Not all of the earlier runtime's data could be taken over: " + failed);
        }
        return "agents: " + agentCount[0] + " taken over, " + agentCount[1] + " there before; sessions: "
                + (sessionCount[0] + sessionCount[2]) + " taken over, " + sessionCount[1] + " there before, "
                + sessionCount[2] + " with a summary";
    }

    /** @return 0 taken over, 1 there before; 2 taken over with a summary */
    private int take(Map<String, Object> old) {
        SessionId id = SessionId.of(text(old, "id").orElseThrow(() -> new IllegalArgumentException("no id")));
        if (sessions.find(id).isPresent()) {
            return 1;
        }
        UserId user = UserId.of(text(old, "userId").orElseThrow(() -> new IllegalArgumentException("no user")));
        Optional<String> conversation = text(old, "conversationId");
        List<StoredMessage> history = conversation.map(c -> conversations.conversation(ConversationId.of(c)))
                .orElse(List.of());
        Map<String, Object> metadata = new LinkedHashMap<>();
        conversation.ifPresent(c -> metadata.put(RepositorySessionStore.CONVERSATION_ID, c));
        metadata.put(ManagedSession.TYPE, text(old, "type").orElse(ManagedSession.CHAT));
        for (String key : KEPT) {
            if (old.get(key) != null) {
                metadata.put(key, old.get(key));
            }
        }
        Optional<ManagedAgent> agent = agentOf(old).flatMap(agentId -> agents.find(AgentId.of(agentId)));
        agent.ifPresent(found -> {
            metadata.put(ManagedSession.AGENT, ManagedSession.REF);
            metadata.put(ManagedSession.AGENT_ID, found.id().value());
        });
        Optional<ConversationSummary> summary = summary(conversation, history);
        summary.ifPresent(found -> metadata.put(SessionSummaryStore.SUMMARY, SessionSummaryStore.stored(found)));
        metadata.put(SessionMessageMigration.MIGRATED_AT, clock.instant().toString());
        Instant started = instant(old.get("startedAt"));
        // The messages first: a session that is there is one that was taken over whole.
        target.restore(id, history);
        sessions.create(new Session(id, user, text(old, "parentSessionId").map(SessionId::of).orElse(null),
                agent.map(ManagedAgent::definition).orElse(null), metadata, started));
        return summary.isPresent() ? 2 : 0;
    }

    /** The session's agent; an office session that named none names its main one among its agents. */
    private static Optional<String> agentOf(Map<String, Object> old) {
        Optional<String> named = text(old, "agentDefinitionId");
        if (named.isPresent() || !(old.get("sessionAgents") instanceof List<?> listed)) {
            return named;
        }
        return listed.stream()
                .filter(entry -> entry instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("main")))
                .map(entry -> ((Map<?, ?>) entry).get("id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst();
    }

    /** The conversation's latest summary, through the last message it covers; empty if it has none. */
    private Optional<ConversationSummary> summary(Optional<String> conversation, List<StoredMessage> history) {
        return conversation.flatMap(earlier::latestSummary).flatMap(summary -> history.stream()
                .filter(stored -> stored.sequence() <= summary.throughSequence())
                .reduce((first, second) -> second)
                .map(stored -> new ConversationSummary(summary.text(), stored.message().id())));
    }

    /**
     * Written as text in the earlier runtime's database, and as seconds since
     * the epoch in its files; a session that has neither started at the epoch.
     */
    private static Instant instant(Object value) {
        if (value instanceof Number seconds) {
            BigDecimal exact = new BigDecimal(seconds.toString());
            return Instant.ofEpochSecond(exact.longValue(), exact.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        return value instanceof String text && !text.isBlank() ? Instant.parse(text) : Instant.EPOCH;
    }

    private static Optional<String> text(Map<String, Object> json, String key) {
        return json.get(key) instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty();
    }
}
