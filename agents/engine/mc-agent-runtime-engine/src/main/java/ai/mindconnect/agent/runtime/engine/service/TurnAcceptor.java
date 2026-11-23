package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.TurnAdmission;
import ai.mindconnect.agent.runtime.api.hook.TurnInput;
import ai.mindconnect.agent.runtime.api.port.out.ModelSettingsResolver;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * Decides what a turn is before it runs: whether it may start at all, as
 * which agent, with which model settings. The same for a turn a caller
 * submits and for one a tool hands its call to, which is why it is one
 * class. It only decides — the turn exists once its task is submitted.
 */
final class TurnAcceptor {

    /** A turn that was accepted, and the message that opens it. */
    record Accepted(Turn turn, TurnMessage userMessage) { }

    private final SessionStore sessions;
    private final Supplier<List<ModelSettingsResolver>> modelSettings;
    private final HookRunner hooks;

    TurnAcceptor(SessionStore sessions,
                 Supplier<List<ModelSettingsResolver>> modelSettings, HookRunner hooks) {
        this.sessions = sessions;
        this.modelSettings = modelSettings;
        this.hooks = hooks;
    }

    /** Refuses at once what can be known at once: a session or an agent that does not exist, input a guard rejects. */
    Accepted accept(TurnRequest submitted) {
        Session session = sessions.find(submitted.sessionId()).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + submitted.sessionId().value()));
        TurnAdmission admission = hooks.run(RuntimeHook.TURN_INPUT, new TurnInput(session, submitted),
                input -> new TurnAdmission.Admitted(input.request()));
        if (admission instanceof TurnAdmission.Refused refused) {
            throw new IllegalArgumentException("The turn was refused: " + refused.reason());
        }
        TurnRequest request = ((TurnAdmission.Admitted) admission).request();
        AgentDefinition agent = agentOf(request, session);
        Turn turn = new Turn(TurnId.of(UUID.randomUUID().toString()), session.id(),
                agent.withModelSettings(modelSettingsOf(session, agent)),
                TurnStatus.QUEUED, List.of(), null, null);
        return new Accepted(turn, TurnMessage.user(request.input()));
    }

    /** The request's agent, else the session's; a turn without either cannot run. */
    private static AgentDefinition agentOf(TurnRequest request, Session session) {
        if (request.agent() != null) {
            return request.agent();
        }
        if (session.agent() == null) {
            throw new IllegalArgumentException(
                    "The request brings no agent and session " + session.id().value() + " has none");
        }
        return session.agent();
    }

    /** The layers laid over each other, strongest first: the agent's, then what the resolvers add. */
    private ModelSettings modelSettingsOf(Session session, AgentDefinition agent) {
        List<ModelSettings> layers = new ArrayList<>(List.of(agent.modelSettings()));
        modelSettings.get().forEach(resolver -> layers.addAll(resolver.layers(session)));
        ModelSettings effective = ModelSettings.NONE;
        for (ModelSettings layer : layers.reversed()) {
            effective = layer.over(effective);
        }
        return effective;
    }
}
