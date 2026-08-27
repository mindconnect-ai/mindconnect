package ai.mindconnect.agent.builder.lmstudio;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.memory.domain.SummaryPlacement;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static ai.mindconnect.agent.builder.lmstudio.LmStudioSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tool-result compression end-to-end against the local LM Studio: several
 * turns each fetch a large tool result; at the start of a later turn the
 * strategy marks the OLD, READ results compressed — the newest three and the
 * stored originals stay untouched (the Claude model: lossless at the store,
 * smaller only in the window).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class CompressionLmStudioTest {

    @BeforeEach
    void requireLmStudio() {
        assumeLmStudio();
        TestTools.reset();
        TestTools.bigChars = 8_000;
    }

    /** Compression on, thresholds forced low so the rules — not the sizes — decide. */
    private static SummarizingWindowConfig aggressiveCompression() {
        return new SummarizingWindowConfig(
                0.90,   // maxConversationRatio
                1.0,    // maxMessageRatio
                true,   // compressToolResults
                50,       // minToolResultCompressTokens
                0.0001,   // toolResultThresholdRatio (validated to (0,1])
                0.0001,   // compressWhenWindowAboveRatio → pressure from the first token
                false,  // autoSummarize
                1.0,    // autoSummarizeRatio
                SummaryPlacement.SYSTEM_PROMPT,
                1_000);
    }

    @Test
    void oldReadResultsCompressTheNewestThreeStayFull() {
        try (AgentRuntime runtime = runtime("compressor", ROBOT_PROMPT,
                aggressiveCompression(), List.of(tool("it_big", false)))) {
            AgentSession session = runtime.openSession("compressor", "tester");

            for (int i = 1; i <= 4; i++) {
                String answer = runtime.chat(session.id(),
                        "Call the tool it_big with text='d" + i + "'. Then answer with one word: OK",
                        event -> { });
                assertThat(answer).as("turn %d must complete", i).isNotBlank();
            }
            List<Message> afterFour = bigResults(runtime, session);
            assumeTrue(afterFour.size() >= 4, "model did not call it_big once per turn");
            assertThat(afterFour.stream().filter(Message::compressed))
                    .as("during a turn nothing UNREAD compresses; with ≤3 read results the keep-recent rule holds")
                    .allSatisfy(m -> assertThat(indexOf(afterFour, m))
                            .isLessThan(afterFour.size() - 3));

            // A fifth, tool-free turn: at its entry the strategy sees 4+ read
            // results and marks everything except the newest three.
            String answer = runtime.chat(session.id(),
                    "What is 2+2? Answer with just the number, no tools.", event -> { });
            assertThat(answer).isNotBlank();

            List<Message> results = bigResults(runtime, session);
            int n = results.size();
            for (int i = 0; i < n; i++) {
                Message m = results.get(i);
                if (i < n - 3) {
                    assertThat(m.compressed())
                            .as("old, read result %d of %d is compressed", i + 1, n).isTrue();
                    assertThat(m.compressedContent())
                            .as("the stub tells the model the way back")
                            .contains("call the tool again");
                    assertThat(m.content())
                            .as("the ORIGINAL is untouched in the store")
                            .hasSizeGreaterThan(TestTools.bigChars);
                } else {
                    assertThat(m.compressed())
                            .as("newest-three result %d of %d stays full", i + 1, n).isFalse();
                }
            }
        }
    }

    @Test
    void masterSwitchOffNeverCompresses() {
        SummarizingWindowConfig off = new SummarizingWindowConfig(
                0.90, 1.0, false, 50, 0.0001, 0.0001, false, 1.0,
                SummaryPlacement.SYSTEM_PROMPT, 1_000);
        try (AgentRuntime runtime = runtime("uncompressed", ROBOT_PROMPT,
                off, List.of(tool("it_big", false)))) {
            AgentSession session = runtime.openSession("uncompressed", "tester");

            for (int i = 1; i <= 4; i++) {
                runtime.chat(session.id(),
                        "Call the tool it_big with text='n" + i + "'. Then answer with one word: OK",
                        event -> { });
            }
            runtime.chat(session.id(), "What is 3+3? Answer with just the number, no tools.",
                    event -> { });

            assertThat(bigResults(runtime, session))
                    .as("compressToolResults=false is a hard off switch")
                    .noneMatch(Message::compressed);
        }
    }

    private static List<Message> bigResults(AgentRuntime runtime, AgentSession session) {
        return history(runtime, session.conversationId()).messages().stream()
                .filter(m -> m.type() == MessageType.TOOL_RESULT)
                .sorted(Comparator.comparingInt(Message::sequenceNum))
                .toList();
    }

    private static int indexOf(List<Message> list, Message m) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(m.id())) return i;
        }
        return -1;
    }
}
