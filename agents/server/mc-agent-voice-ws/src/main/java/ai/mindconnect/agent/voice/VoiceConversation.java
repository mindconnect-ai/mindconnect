package ai.mindconnect.agent.voice;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.voice.protocol.VoiceCommand;
import ai.mindconnect.agent.voice.protocol.VoiceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

/**
 * One connection's conversation with one agent session: collects the audio of
 * an utterance, runs a {@link VoiceTurn} for it, and cancels the running turn
 * when the caller interrupts. The scope and user were taken from the
 * handshake; every piece of work runs inside that scope again, because the
 * connection's messages arrive on threads that never saw the request.
 */
public final class VoiceConversation {

    private static final Logger log = LoggerFactory.getLogger(VoiceConversation.class);

    private final VoicePorts ports;
    private final Scope scope;
    private final UserId user;
    private final VoiceOutput output;
    private final ByteArrayOutputStream utterance = new ByteArrayOutputStream();
    private VoiceCommand.Start start;
    private SessionId sessionId;
    private boolean speak;
    private VoiceTurn turn;

    /** @param scope the handshake's scope, or {@code null} when the host binds none */
    public VoiceConversation(VoicePorts ports, Scope scope, UserId user, VoiceOutput output) {
        this.ports = ports;
        this.scope = scope;
        this.user = user;
        this.output = output;
    }

    public synchronized void start(VoiceCommand.Start command) {
        if (sessionId != null) {
            output.send(new VoiceEvent.Error("already-started", "The conversation is already open"));
            return;
        }
        inScope(() -> {
            AgentSession session = openSession(command);
            start = command;
            sessionId = session.id();
            speak = ports.speech() != null
                    && ports.llmConfigs().findResolvedByName(ports.settings().textToSpeechConfig()).isPresent();
        });
        log.info("Voice conversation open: session {} for {} (speech {})", sessionId, user, speak ? "on" : "off");
        output.send(new VoiceEvent.Ready(sessionId.value(), speak));
        if (command.message() != null && !command.message().isBlank()) {
            run(VoiceTurn.Input.ofText(command.message()));
        }
    }

    public synchronized void audio(byte[] chunk) {
        if (utterance.size() + chunk.length > ports.settings().maxUtteranceBytes()) {
            utterance.reset();
            output.send(new VoiceEvent.Error("utterance-too-long", "The recording is too long for one turn"));
            return;
        }
        utterance.writeBytes(chunk);
    }

    public synchronized void endOfUtterance() {
        byte[] audio = utterance.toByteArray();
        utterance.reset();
        if (audio.length == 0) {
            output.send(new VoiceEvent.Error("nothing-heard", "No audio arrived for this turn"));
            return;
        }
        run(VoiceTurn.Input.ofAudio(audio, start == null ? null : start.inputFormat()));
    }

    public synchronized void text(String text) {
        if (text == null || text.isBlank()) return;
        run(VoiceTurn.Input.ofText(text));
    }

    public synchronized void cancel() {
        if (turn != null) turn.cancel();
        turn = null;
        utterance.reset();
    }

    public void close() {
        cancel();
    }

    /** Starts a turn on its own thread; a turn still running is interrupted by the new one. */
    private void run(VoiceTurn.Input input) {
        if (sessionId == null) {
            output.send(new VoiceEvent.Error("not-started", "Send a start message first"));
            return;
        }
        cancel();
        String language = start.language() != null ? start.language() : ports.settings().language();
        VoiceTurn next = new VoiceTurn(ports, sessionId, input, language, start.voice(), speak, output);
        turn = next;
        Thread.ofVirtual().name("voice-turn-" + sessionId.value()).start(() -> inScope(next));
    }

    private AgentSession openSession(VoiceCommand.Start command) {
        if (command.sessionId() != null && !command.sessionId().isBlank()) {
            return ports.sessions().findById(new SessionId(command.sessionId()))
                    .filter(session -> user.equals(session.userId()))
                    .orElseThrow(() -> new IllegalArgumentException("No such session: " + command.sessionId()));
        }
        if (command.agent() == null || command.agent().isBlank()) {
            throw new IllegalArgumentException("Name an agent or a session to continue");
        }
        AgentDefinition agent = ports.agents().findByName(command.agent())
                .or(() -> ports.agents().findById(AgentId.of(command.agent())))
                .orElseThrow(() -> new IllegalArgumentException("No such agent: " + command.agent()));
        return ports.sessionService().openChat(agent.id(), user);
    }

    private void inScope(Runnable work) {
        if (scope != null && ports.scope() instanceof ThreadBoundScope bound) {
            bound.runIn(scope, work);
        } else {
            work.run();
        }
    }
}
