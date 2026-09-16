package ai.mindconnect.chatui.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a client joining a running turn is handed before the live feed: the
 * cards nothing persisted can rebuild yet, then the reply bubble and its text.
 */
class SessionStreamsCatchUpTest {

    private final SessionStreams streams = new SessionStreams();

    @Test
    void aCardIsReplacedInPlaceAndKeepsItsOrder() {
        streams.turnStarted("c");
        streams.rememberCard("c", "think-0", "{think-0 v1}");
        streams.rememberCard("c", "think-1", "{think-1 v1}");
        streams.rememberCard("c", "think-0", "{think-0 v2}");

        assertThat(streams.catchUp("c").orElseThrow().cardPatches())
                .containsExactly("{think-0 v2}", "{think-1 v1}");
    }

    @Test
    void theBubbleDoesNotDropTheCards() {
        streams.turnStarted("c");
        streams.rememberCard("c", "think-0", "{think}");
        streams.rememberBubble("c", "{bubble}");
        streams.rememberText("c", "{text}");

        var catchUp = streams.catchUp("c").orElseThrow();
        assertThat(catchUp.cardPatches()).containsExactly("{think}");
        assertThat(catchUp.bubblePatch()).isEqualTo("{bubble}");
        assertThat(catchUp.textPatch()).isEqualTo("{text}");
    }

    @Test
    void aForgottenCardIsNotReplayed() {
        streams.turnStarted("c");
        streams.rememberCard("c", "think-0", "{think}");
        streams.forgetCard("c", "think-0");

        assertThat(streams.catchUp("c").orElseThrow().cardPatches()).isEmpty();
    }

    @Test
    void theNextTurnStartsClean() {
        streams.turnStarted("c");
        streams.rememberCard("c", "think-0", "{think}");
        streams.turnEnded("c");

        assertThat(streams.catchUp("c")).isEmpty();
    }
}
