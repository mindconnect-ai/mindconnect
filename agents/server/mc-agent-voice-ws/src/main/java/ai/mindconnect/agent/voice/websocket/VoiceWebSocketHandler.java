package ai.mindconnect.agent.voice.websocket;

import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.voice.VoiceConversation;
import ai.mindconnect.agent.voice.VoiceOutput;
import ai.mindconnect.agent.voice.VoicePorts;
import ai.mindconnect.agent.voice.protocol.VoiceCommand;
import ai.mindconnect.agent.voice.protocol.VoiceEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WebSocket side of a voice conversation: decodes the client's commands,
 * hands them and the audio frames to the connection's
 * {@link VoiceConversation}, and writes its events back. Sends go through a
 * {@link ConcurrentWebSocketSessionDecorator}, because a turn's tokens and
 * its speech come from different threads.
 */
public final class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final int TEXT_LIMIT_BYTES = 1024 * 1024;

    private final VoicePorts ports;
    private final ObjectMapper json;
    private final Map<String, VoiceConversation> conversations = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(VoicePorts ports, ObjectMapper json) {
        this.ports = ports;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Per connection rather than on the container, so a host's mock-servlet tests still start.
        session.setBinaryMessageSizeLimit(ports.settings().maxUtteranceBytes());
        session.setTextMessageSizeLimit(TEXT_LIMIT_BYTES);
        var out = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);
        UserId user = (UserId) session.getAttributes().get(VoiceHandshakeInterceptor.USER);
        Scope scope = (Scope) session.getAttributes().get(VoiceHandshakeInterceptor.SCOPE);
        conversations.put(session.getId(), new VoiceConversation(ports, scope, user, new SessionOutput(out)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        VoiceConversation conversation = conversations.get(session.getId());
        try {
            switch (json.readValue(message.getPayload(), VoiceCommand.class)) {
                case VoiceCommand.Start start -> conversation.start(start);
                case VoiceCommand.AudioEnd ignored -> conversation.endOfUtterance();
                case VoiceCommand.Text text -> conversation.text(text.text());
                case VoiceCommand.Cancel ignored -> conversation.cancel();
                case VoiceCommand.Bye ignored -> session.close(CloseStatus.NORMAL);
            }
        } catch (JsonProcessingException e) {
            reply(session, new VoiceEvent.Error("bad-message", "Unreadable message: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            reply(session, new VoiceEvent.Error("bad-request", e.getMessage()));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var payload = message.getPayload();
        byte[] chunk = new byte[payload.remaining()];
        payload.get(chunk);
        conversations.get(session.getId()).audio(chunk);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        VoiceConversation conversation = conversations.remove(session.getId());
        if (conversation != null) conversation.close();
    }

    private void reply(WebSocketSession session, VoiceEvent event) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(event)));
    }

    /** The conversation's events as frames on the decorated session. */
    private final class SessionOutput implements VoiceOutput {

        private final WebSocketSession session;

        SessionOutput(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void send(VoiceEvent event) {
            try {
                if (session.isOpen()) session.sendMessage(new TextMessage(json.writeValueAsString(event)));
            } catch (IOException e) {
                log.debug("Could not send {} to voice connection {}: {}", event.type(), session.getId(), e.getMessage());
            }
        }

        @Override
        public void sendAudio(byte[] audio) {
            try {
                if (session.isOpen()) session.sendMessage(new BinaryMessage(audio));
            } catch (IOException e) {
                log.debug("Could not send audio to voice connection {}: {}", session.getId(), e.getMessage());
            }
        }
    }
}
