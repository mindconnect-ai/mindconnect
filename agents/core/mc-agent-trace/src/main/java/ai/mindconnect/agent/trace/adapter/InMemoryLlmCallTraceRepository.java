package ai.mindconnect.agent.trace.adapter;

import ai.mindconnect.agent.scope.NamespaceMap;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
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
 * {@link #maxTracesPerSession} traces per session (default
 * {@value #DEFAULT_MAX_PER_SESSION}), dropping the oldest on save. A trace
 * carries the verbatim request and response, so an unbounded map would grow
 * with every call of a long-lived embedded runtime.
 */
public class InMemoryLlmCallTraceRepository implements LlmCallTraceRepository {

    /** The same cap the file adapter applies. {@code 0} or less keeps everything. */
    public static final int DEFAULT_MAX_PER_SESSION = 50;

    private final ScopeSupplier scope;
    private final NamespaceMap<Map<TraceId, LlmCallTrace>> store;
    private final int maxTracesPerSession;

    public InMemoryLlmCallTraceRepository(ScopeSupplier scope) {
        this(DEFAULT_MAX_PER_SESSION, scope);
    }

    public InMemoryLlmCallTraceRepository(int maxTracesPerSession, ScopeSupplier scope) {
        this.maxTracesPerSession = maxTracesPerSession;
        this.scope = scope;
        this.store = new NamespaceMap<>(ConcurrentHashMap::new);
    }

    @Override
    public void save(LlmCallTrace trace) {
        store.of(scope.namespace()).put(trace.id(), trace);
        enforceRetention(trace.context().sessionId());
    }

    private void enforceRetention(SessionId sessionId) {
        if (maxTracesPerSession <= 0 || sessionId == null) return;
        List<LlmCallTrace> all = findBySession(sessionId);
        if (all.size() <= maxTracesPerSession) return;
        all.stream()
                .sorted(Comparator.comparing((LlmCallTrace t) -> t.startedAt() == null ? Instant.MIN : t.startedAt())
                        .thenComparing(t -> t.id().value()))
                .limit(all.size() - maxTracesPerSession)
                .forEach(t -> store.of(scope.namespace()).remove(t.id()));
    }

    @Override
    public List<LlmCallTrace> findByTurn(String turnId) {
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
    public List<LlmCallTrace> findDescendants(String rootTurnId) {
        // Grow the set of turns reachable from rootTurnId by following parentTurnId links.
        Set<String> turnsInTree = new HashSet<>();
        turnsInTree.add(rootTurnId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (LlmCallTrace t : store.of(scope.namespace()).values()) {
                String parent = t.context().parentTurnId();
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
