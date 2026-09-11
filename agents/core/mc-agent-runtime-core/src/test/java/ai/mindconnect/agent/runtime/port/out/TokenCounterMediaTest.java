package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Media has no text to count, so the budget takes an estimate: a flat one
 * per image, a byte-sized one per document. Text-only messages count as
 * they always did.
 */
class TokenCounterMediaTest {

    /** One token per character — makes the sums readable. */
    private final TokenCounter counter = text -> text == null ? 0 : text.length();

    @Test
    void textOnlyMessagesCountAsBefore() {
        int tokens = counter.countMessages(List.of(LlmMessage.user("hello")));
        assertThat(tokens).isEqualTo(4 + 5);
    }

    @Test
    void anImageCostsTheFlatEstimate() {
        LlmMessage user = LlmMessage.user(List.of(
                new LlmContent.Text("what is this?"),
                new LlmContent.Image("QUJD", "image/png")));

        assertThat(counter.countMessages(List.of(user)))
                .isEqualTo(4 + "what is this?".length() + TokenCounter.IMAGE_TOKENS);
    }

    @Test
    void aDocumentIsSizedByItsBytes_neverBelowTheMinimum() {
        // 400,000 base64 chars decode to 300,000 bytes -> 3,000 tokens
        String big = "QUJD".repeat(100_000);
        assertThat(counter.countPart(new LlmContent.Document(big, "application/pdf", "big.pdf")))
                .isEqualTo(300_000 / TokenCounter.DOCUMENT_BYTES_PER_TOKEN);

        assertThat(counter.countPart(new LlmContent.Document("UERG", "application/pdf", "tiny.pdf")))
                .isEqualTo(TokenCounter.DOCUMENT_MIN_TOKENS);
    }
}
