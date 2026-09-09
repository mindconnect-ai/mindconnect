package ai.mindconnect.message.adapter;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.adapter.file.FileMessageRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.out.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Handing out a sequence number is the store's job, and no two appenders
 * may leave with the same one. Both stores that live in this module are
 * held to it: the numbers run 1, 2, 3 …, a store with messages already in
 * it carries on from the highest, and twenty threads appending at the same
 * moment produce twenty different numbers. Reading the highest number and
 * writing the row apart is what used to give a busy turn two messages
 * claiming the same place in the conversation.
 */
class AppendGivesEachMessageItsOwnNumberTest {

    @TempDir
    static Path dir;

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();

    static List<MessageRepository> stores() {
        return List.of(new InMemoryMessageRepository(),
                new FileMessageRepository(dir.resolve(UUID.randomUUID().toString()),
                        new ObjectMapper().registerModule(new JavaTimeModule())));
    }

    private static Message message(UUID conversation, int seq) {
        return Message.of(conversation, SENDER, ParticipantType.USER, MessageType.CHAT, "m" + seq, seq);
    }

    @ParameterizedTest
    @MethodSource("stores")
    void theNumbersRunFromOne_andAStoreWithMessagesCarriesOn(MessageRepository store) {
        UUID conversation = UUID.randomUUID();

        assertThat(store.append(conversation, seq -> message(conversation, seq)).sequenceNum()).isEqualTo(1);
        assertThat(store.append(conversation, seq -> message(conversation, seq)).sequenceNum()).isEqualTo(2);

        UUID older = UUID.randomUUID();
        IntStream.rangeClosed(1, 7).forEach(seq -> store.save(message(older, seq)));
        assertThat(store.append(older, seq -> message(older, seq)).sequenceNum())
                .as("picks up above what is already stored").isEqualTo(8);

        assertThat(store.append(UUID.randomUUID(), s -> message(UUID.randomUUID(), s)).sequenceNum())
                .as("each conversation counts on its own").isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("stores")
    void twentyThreadsAppendingAtOnceGetTwentyDifferentNumbers(MessageRepository store) throws Exception {
        int writers = 20;
        UUID conversation = UUID.randomUUID();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(writers);
        var failures = new CopyOnWriteArrayList<Throwable>();

        try (var pool = Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        store.append(conversation, seq -> message(conversation, seq));
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures).isEmpty();
        assertThat(store.findByConversationId(conversation, new PageRequest(0, 100)))
                .extracting(Message::sequenceNum)
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, writers).boxed().toList());
    }
}
