package ai.mindconnect.message.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The message JSON on disk: a message made of parts comes back as the same
 * parts (the {@code kind} discriminator does its job), and a file written
 * before parts existed still loads.
 */
class FileMessageRepositoryTest {

    private final ConversationId conversation = ConversationId.random();
    private final String sender = UUID.randomUUID().toString();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path dir;

    private FileMessageRepository repo;

    @BeforeEach
    void setUp() {
        repo = new FileMessageRepository(dir, json, new Namespace("test"));
    }

    @Test
    void aMessageMadeOfPartsComesBackAsTheSameParts() throws Exception {
        Message m = Message.of(conversation, sender, ParticipantType.USER, MessageType.CHAT, List.of(
                new ContentPart.Text("see attached"),
                new ContentPart.Image("f-1", "photo.png", "image/png", 240_000L),
                new ContentPart.File("f-2", "spec.pdf", "application/pdf", 1_048_576L)), 1);
        repo.save(m);

        assertThat(repo.findById(conversation, m.id())).contains(m);

        String written = Files.readString(onlyFile());
        assertThat(written).contains("\"kind\":\"text\"", "\"kind\":\"image\"", "\"kind\":\"file\"");
        assertThat(written).contains("\"content\":\"see attached\"");
    }

    @Test
    void aTextMessageHasNoPartsInItsJson() throws Exception {
        Message m = Message.of(conversation, sender, ParticipantType.USER, MessageType.CHAT, "hello", 1);
        repo.save(m);

        assertThat(repo.findById(conversation, m.id())).contains(m);
        assertThat(Files.readString(onlyFile())).contains("\"parts\":null");
    }

    @Test
    void aFileWrittenBeforePartsExistedStillLoads() throws Exception {
        String id = UUID.randomUUID().toString();
        Path messages = dir.resolve("test").resolve("conversations").resolve(conversation.value()).resolve("messages");
        Files.createDirectories(messages);
        Files.writeString(messages.resolve("1_" + id + ".json"), """
                {"id":"%s","conversationId":"%s","senderId":"%s","senderType":"USER",
                 "recipientId":null,"type":"CHAT","content":"legacy text","metadata":{},
                 "sequenceNum":1,"sentAt":1.7E9,"compressed":false,"compressedContent":null,
                 "tokenCount":3,"compressedTokenCount":null,"durationMs":null,"turnId":null,"run":null}
                """.formatted(id, conversation.value(), sender));

        List<Message> history = repo.findByConversation(conversation, new PageRequest(0, 10));

        assertThat(history).hasSize(1);
        Message m = history.get(0);
        assertThat(m.content()).isEqualTo("legacy text");
        assertThat(m.id()).isEqualTo(MessageId.of(id));
        assertThat(m.parts()).isNull();
        assertThat(m.partsOrText()).containsExactly(new ContentPart.Text("legacy text"));
    }

    private Path onlyFile() throws Exception {
        try (var files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
    }
}
