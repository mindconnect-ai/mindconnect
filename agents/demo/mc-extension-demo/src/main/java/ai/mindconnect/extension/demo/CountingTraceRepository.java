package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.agent.runtime.domain.view.LlmCallTraceHeader;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;

import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;

import java.util.List;
import java.util.Optional;

/**
 * The demo's decorator around the core's trace store: the runtime saves one
 * trace per LLM call, so counting the saves counts the calls — every one of
 * them, sub-agents included, without touching the runtime. Everything else
 * is passed through untouched.
 */
final class CountingTraceRepository implements LlmCallTraceRepository {

    private final LlmCallTraceRepository delegate;
    private final LlmCallCounter counter;

    CountingTraceRepository(LlmCallTraceRepository delegate, LlmCallCounter counter) {
        this.delegate = delegate;
        this.counter = counter;
    }

    @Override
    public void save(LlmCallTrace trace) {
        counter.increment();
        delegate.save(trace);
    }

    @Override
    public List<LlmCallTrace> findByTurn(ChatTurnId turn) {
        return delegate.findByTurn(turn);
    }

    @Override
    public List<LlmCallTrace> findBySession(SessionId session) {
        return delegate.findBySession(session);
    }

    @Override
    public List<LlmCallTrace> findByConversation(ConversationId conversation) {
        return delegate.findByConversation(conversation);
    }

    @Override
    public List<? extends LlmCallTraceHeader> findHeadersByConversation(ConversationId conversation) {
        return delegate.findHeadersByConversation(conversation);
    }

    @Override
    public List<LlmCallTrace> findDescendants(ChatTurnId root) {
        return delegate.findDescendants(root);
    }

    @Override
    public Optional<LlmCallTrace> findById(TraceId id) {
        return delegate.findById(id);
    }

    @Override
    public void deleteBySession(SessionId session) {
        delegate.deleteBySession(session);
    }
}