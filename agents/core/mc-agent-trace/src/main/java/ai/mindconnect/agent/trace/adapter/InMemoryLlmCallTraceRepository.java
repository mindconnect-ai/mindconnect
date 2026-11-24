package ai.mindconnect.agent.trace.adapter;

import ai.mindconnect.agent.scope.NamespaceMap;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.agent.trace.domain.TraceId;

import ai.mindconnect.agent.trace.domain.LlmCallTrace;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link LlmCallTraceRepository} — process-lifetime storage, no persistence.
 *
 * <p><b>Retention:</b> like the file adapter, it keeps at most
 * {@link #maxTracesPerConversation} traces per conversation (default
 * {@value #DEFAULT_MAX_PER_CONVERSATION}), dropping the oldest on save. A trace
 * carries the verbatim request and response, so an unbounded map would grow
 * with every call of a long-lived embedded runtime.
 */
public class InMemoryLlmCallTraceRepository implements LlmCallTraceRepository {

    /** The same cap the file adapter applies. {@code 0} or less keeps everything. */
    public static final int DEFAULT_MAX_PER_CONVERSATION = 50;

    private final ScopeSupplier scope;
    private final NamespaceMap<Map<TraceId, LlmCallTrace>> store;
    private final int maxTracesPerConversation;

    public InMemoryLlmCallTraceRepository(ScopeSupplier scope) {
        this(DEFAULT_MAX_PER_CONVERSATION, scope);
    }

    public InMemoryLlmCallTraceRepository(int maxTracesPerConversation, ScopeSupplier scope) {
        this.maxTracesPerConversation = maxTracesPerConversation;
        this.scope = scope;
        this.store = new NamespaceMap<>(ConcurrentHashMap::new);
    }

    @Override
    public void save(LlmCallTrace trace) {
        store.of(scope.namespace()).put(trace.id(), trace);
        enforceRetention(trace.context().conversationId());
    }

    private void enforceRetention(ConversationId conversationId) {
        if (maxTracesPerConversation <= 0 || conversationId == null) return;
        List<LlmCallTrace> all = findByConversation(conversationId);
        if (all.size() <= maxTracesPerConversation) return;
        all.stream()
                .sorted(Comparator.comparing((LlmCallTrace t) -> t.startedAt() == null ? Instant.MIN : t.startedAt())
                        .thenComparing(t -> t.id().value()))
                .limit(all.size() - maxTracesPerConversation)
                .forEach(t -> store.of(scope.namespace()).remove(t.id()));
    }

    @Override
    public List<LlmCallTrace> findByTurn(ChatTurnId turnId) {
        return store.of(scope.namespace()).values().stream()
                .filter(t -> Objects.equals(t.context().turnId(), turnId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findBySession(SessionId sessionId) {
        return store.of(scope.namespace()).values().stream()
                .filter(t -> Objects.equals(t.context().sessionId(), sessionId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findByConversation(ConversationId conversationId) {
        return store.of(scope.namespace()).values().stream()
                .filter(t -> Objects.equals(t.context().conversationId(), conversationId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findDescendants(ChatTurnId rootTurnId) {
        // Grow the set of turns reachable from rootTurnId by following parentTurnId links.
        Set<ChatTurnId> turnsInTree = new HashSet<>();
        turnsInTree.add(rootTurnId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (LlmCallTrace t : store.of(scope.namespace()).values()) {
                ChatTurnId parent = t.context().parentTurnId();
                if (parent != null && turnsInTree.contains(parent) && turnsInTree.add(t.context().turnId())) {
                    changed = true;
                }
            }
        }
        return store.of(scope.namespace()).values().stream()
                .filter(t -> turnsInTree.contains(t.context().turnId()))
                .toList();
    }

    @Override
    public Optional<LlmCallTrace> findById(TraceId id) {
        return Optional.ofNullable(store.of(scope.namespace()).get(id));
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        store.of(scope.namespace()).values().removeIf(t -> Objects.equals(t.context().sessionId(), sessionId));
    }
}
