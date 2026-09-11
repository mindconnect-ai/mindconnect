package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One file per summary: a compaction after the turn and one started from the UI
 * save into the same conversation without dropping each other's summary.
 */
class FileConversationSummaryRepositoryTest {

    @Test
    void summariesComeBackInSequenceOrderEachInItsOwnFile(@TempDir Path dir) {
        FileConversationSummaryRepository repo = new FileConversationSummaryRepository(dir, new Namespace("test"));
        ConversationId conversation = ConversationId.random();
        ConversationSummary later = ConversationSummary.create(conversation, 11, 20, 10, "later");
        ConversationSummary earlier = ConversationSummary.create(conversation, 1, 10, 10, "earlier");

        repo.save(later);
        repo.save(earlier);

        assertThat(repo.findByConversation(conversation)).containsExactly(earlier, later);
        assertThat(dir.resolve("test/conversations/" + conversation.value() + "/summaries/0000000001_"
                + earlier.id().value() + ".json")).exists();
        assertThat(repo.findByConversation(ConversationId.random())).isEmpty();
    }

    @Test
    void concurrentSavesAllLand(@TempDir Path dir) throws Exception {
        FileConversationSummaryRepository repo = new FileConversationSummaryRepository(dir, new Namespace("test"));
        ConversationId conversation = ConversationId.random();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int from = i * 10 + 1;
                futures.add(pool.submit(() ->
                        repo.save(ConversationSummary.create(conversation, from, from + 9, 10, "part " + from))));
            }
            for (Future<?> future : futures) future.get();
        }

        assertThat(repo.findByConversation(conversation)).extracting(ConversationSummary::fromSequenceNum)
                .containsExactlyElementsOf(IntStream.range(0, 50).map(i -> i * 10 + 1).boxed().toList());
    }

    @Test
    void deletingAConversationsSummariesLeavesOthersAlone(@TempDir Path dir) {
        FileConversationSummaryRepository repo = new FileConversationSummaryRepository(dir, new Namespace("test"));
        ConversationId gone = ConversationId.random();
        ConversationId kept = ConversationId.random();
        repo.save(ConversationSummary.create(gone, 1, 5, 5, "gone"));
        ConversationSummary keep = ConversationSummary.create(kept, 1, 5, 5, "kept");
        repo.save(keep);

        repo.deleteByConversation(gone);

        assertThat(repo.findByConversation(gone)).isEmpty();
        assertThat(repo.findByConversation(kept)).containsExactly(keep);
    }

    @Test
    void anUnreadableSummaryCountsAsNoneAndIsNotOverwritten(@TempDir Path dir) throws Exception {
        FileConversationSummaryRepository repo = new FileConversationSummaryRepository(dir, new Namespace("test"));
        ConversationId conversation = ConversationId.random();
        ConversationSummary summary = ConversationSummary.create(conversation, 1, 5, 5, "gist");
        repo.save(summary);
        Path file = dir.resolve("test/conversations/" + conversation.value() + "/summaries/0000000001_"
                + summary.id().value() + ".json");
        Files.writeString(file, "{\"id\":");

        assertThat(repo.findByConversation(conversation)).isEmpty();
        repo.save(ConversationSummary.create(conversation, 6, 10, 5, "next"));
        assertThat(Files.readString(file)).isEqualTo("{\"id\":");
    }
}
