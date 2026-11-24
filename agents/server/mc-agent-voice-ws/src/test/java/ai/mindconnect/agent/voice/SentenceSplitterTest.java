package ai.mindconnect.agent.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Sentences come out as soon as they are complete, however the tokens are cut. */
class SentenceSplitterTest {

    @Test
    void emitsEachSentenceOnceItsEndArrives() {
        SentenceSplitter splitter = new SentenceSplitter();
        List<String> out = new ArrayList<>();
        for (String token : List.of("Guten Mor", "gen! Wie war ", "dein Tag", "? Erzähl mal", ".")) {
            out.addAll(splitter.add(token));
        }
        assertThat(out).containsExactly("Guten Morgen!", "Wie war dein Tag?");
        assertThat(splitter.flush()).isEqualTo("Erzähl mal.");
    }

    @Test
    void anOrdinalDoesNotEndASentence() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertThat(splitter.add("Am 24. November war es kalt. Und dann")).containsExactly("Am 24. November war es kalt.");
    }

    @Test
    void aVeryShortPieceWaitsForMore() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertThat(splitter.add("Ja. Das stimmt wirklich. ")).containsExactly("Ja. Das stimmt wirklich.");
    }
}
