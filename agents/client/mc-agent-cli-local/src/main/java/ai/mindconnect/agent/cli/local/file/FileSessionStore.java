package ai.mindconnect.agent.cli.local.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.service.AgentDefinitionConverter;
import ai.mindconnect.agent.runtime.engine.service.TurnMessageConverter;

/**
 * Sessions as files, one folder each: {@code session.json} for what the
 * session is, {@code messages.jsonl} for what was said — one message a
 * line, appended and never rewritten, so a session that was cut off is
 * complete up to its last line.
 */
public final class FileSessionStore implements SessionStore {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final Path sessionsDirectory;
    private final ObjectMapper json;
    private final TurnMessageConverter messages = new TurnMessageConverter();
    private final AgentDefinitionConverter agents = new AgentDefinitionConverter();

    public FileSessionStore(Path sessionsDirectory, ObjectMapper json) {
        this.sessionsDirectory = sessionsDirectory;
        this.json = json;
    }

    @Override
    public synchronized void save(Session session) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("id", session.id().value());
        content.put("userId", session.userId().value());
        content.put("parentId", session.parentId() == null ? null : session.parentId().value());
        content.put("agent", session.agent() == null ? null : agents.toJson(session.agent()));
        content.put("metadata", session.metadata());
        content.put("createdAt", session.createdAt().toString());
        try {
            Files.createDirectories(folder(session.id()));
            json.writerWithDefaultPrettyPrinter().writeValue(sessionFile(session.id()).toFile(), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write session " + session.id(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Session> find(SessionId sessionId) {
        if (!Files.isRegularFile(sessionFile(sessionId))) {
            return Optional.empty();
        }
        try {
            Map<String, Object> content = json.readValue(sessionFile(sessionId).toFile(), JSON_OBJECT);
            return Optional.of(new Session(
                    sessionId,
                    UserId.of((String) content.get("userId")),
                    content.get("parentId") == null ? null : SessionId.of((String) content.get("parentId")),
                    content.get("agent") instanceof Map<?, ?> agent ? agents.fromJson((Map<String, Object>) agent) : null,
                    (Map<String, Object>) content.get("metadata"),
                    Instant.parse((String) content.get("createdAt"))));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read session " + sessionId, e);
        }
    }

    @Override
    public synchronized Session update(SessionId sessionId, UnaryOperator<Session> change) {
        Session changed = change.apply(find(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value())));
        save(changed);
        return changed;
    }

    @Override
    public synchronized List<TurnMessage> messages(SessionId sessionId) {
        if (!Files.isRegularFile(messagesFile(sessionId))) {
            return List.of();
        }
        try {
            List<TurnMessage> conversation = new ArrayList<>();
            for (String line : Files.readAllLines(messagesFile(sessionId))) {
                if (!line.isBlank()) {
                    conversation.add(messages.fromJson(json.readValue(line, JSON_OBJECT)));
                }
            }
            return conversation;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the messages of session " + sessionId, e);
        }
    }

    @Override
    public synchronized List<TurnMessage> append(SessionId sessionId, List<TurnMessage> added) {
        List<TurnMessage> stored = added.stream()
                .map(message -> message.stored(UUID.randomUUID().toString()))
                .toList();
        try {
            StringBuilder lines = new StringBuilder();
            for (TurnMessage message : stored) {
                lines.append(json.writeValueAsString(messages.toJson(message))).append('\n');
            }
            Files.createDirectories(folder(sessionId));
            Files.writeString(messagesFile(sessionId), lines,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to the messages of session " + sessionId, e);
        }
        return stored;
    }

    private Path folder(SessionId sessionId) {
        return sessionsDirectory.resolve(sessionId.value());
    }

    private Path sessionFile(SessionId sessionId) {
        return folder(sessionId).resolve("session.json");
    }

    private Path messagesFile(SessionId sessionId) {
        return folder(sessionId).resolve("messages.jsonl");
    }
}
