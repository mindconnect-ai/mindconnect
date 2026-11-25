package ai.mindconnect.agent.runtime.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * What every {@link SessionStore} does, whatever holds the messages. A store
 * that is replaced by another has to keep this, or the sessions written by
 * the one are not what the other reads.
 *
 * <p>A store this does not hold for in every point says so by overriding the
 * question that names the point, and the test then holds what it does
 * instead.
 */
public abstract class SessionStoreContract {

    /** A store of its own, empty, for the one test that asks. */
    protected abstract SessionStore store();

    /**
     * Whether a message appended with an id keeps it. The migration of
     * stored sessions to another store needs that: metadata and traces name
     * messages by their id.
     */
    protected boolean keepsAGivenId() {
        return true;
    }

    private static Session aSession(String id) {
        return new Session(SessionId.of(id), UserId.of("ada"), null, null, Map.of("cwd", "/tmp/project"),
                Instant.parse("2026-11-25T10:00:00Z"));
    }

    @Test
    void a_session_comes_back_as_it_was_created_and_is_created_once() {
        SessionStore store = store();
        Session session = aSession("s1");

        store.create(session);

        assertThat(store.find(session.id())).contains(session);
        assertThatThrownBy(() -> store.create(session)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_session_is_changed_in_place() {
        SessionStore store = store();
        Session session = aSession("s1");
        store.create(session);

        Session changed = store.update(session.id(), s -> s.withMetadata("title", "Pictures"));

        assertThat(changed.metadata()).containsEntry("title", "Pictures").containsEntry("cwd", "/tmp/project");
        assertThat(store.find(session.id())).contains(changed);
    }

    @Test
    void append_gives_every_message_an_id_and_keeps_the_order() {
        SessionStore store = store();
        Session session = aSession("s1");
        store.create(session);

        List<TurnMessage> first = store.append(session.id(), List.of(
                TurnMessage.user(List.of(new ContentPart.Text("Hello"))).inTurn("t1"),
                TurnMessage.assistant("Hello back").inTurn("t1")));
        List<TurnMessage> second = store.append(session.id(), List.of(
                TurnMessage.user(List.of(new ContentPart.Text("Again"))).inTurn("t2")));

        assertThat(first).extracting(TurnMessage::id).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(store.messages(session.id())).extracting(TurnMessage::id).containsExactly(
                first.get(0).id(), first.get(1).id(), second.get(0).id());
        assertThat(store.messages(session.id())).extracting(TurnMessage::text)
                .containsExactly("Hello", "Hello back", "Again");
    }

    @Test
    void what_was_appended_comes_back_as_it_was_written() {
        SessionStore store = store();
        Session session = aSession("s1");
        store.create(session);
        List<TurnMessage> written = List.of(
                TurnMessage.user(List.of(new ContentPart.Text("What is on this?"),
                        ContentPart.Image.of(new ContentPart.MediaSource.Url("https://example.org/a.png"))))
                        .inTurn("t1"),
                TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "bash", Map.of("command", "ls"))))
                        .inTurn("t1"),
                TurnMessage.toolResult("c1", "bash", "a.png", false).inTurn("t1"),
                TurnMessage.assistant("A picture.").inTurn("t1"));

        List<TurnMessage> stored = store.append(session.id(), written);

        assertThat(store.messages(session.id())).isEqualTo(stored);
        assertThat(stored).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id").isEqualTo(written);
    }

    @Test
    void the_messages_of_one_turn_are_those_the_turn_wrote() {
        SessionStore store = store();
        Session session = aSession("s1");
        store.create(session);
        store.append(session.id(), List.of(
                TurnMessage.user(List.of(new ContentPart.Text("one"))).inTurn("t1"),
                TurnMessage.user(List.of(new ContentPart.Text("two"))).inTurn("t2"),
                TurnMessage.assistant("two back").inTurn("t2")));

        assertThat(store.messages(session.id(), ai.mindconnect.agent.runtime.api.domain.TurnId.of("t2")))
                .extracting(TurnMessage::text).containsExactly("two", "two back");
    }

    @Test
    void a_session_holds_only_its_own_messages() {
        SessionStore store = store();
        Session one = aSession("s1");
        Session other = aSession("s2");
        store.create(one);
        store.create(other);

        store.append(one.id(), List.of(TurnMessage.assistant("to one")));
        store.append(other.id(), List.of(TurnMessage.assistant("to the other")));

        assertThat(store.messages(one.id())).extracting(TurnMessage::text).containsExactly("to one");
        assertThat(store.messages(other.id())).extracting(TurnMessage::text).containsExactly("to the other");
    }

    @Test
    void a_change_of_metadata_changes_that_message_and_keeps_what_it_said() {
        SessionStore store = store();
        Session session = aSession("s1");
        store.create(session);
        List<TurnMessage> stored = store.append(session.id(), List.of(
                TurnMessage.toolResult("c1", "bash", "a long listing", false),
                TurnMessage.assistant("Done.")));

        store.updateMessageMetadata(session.id(), stored.get(0).id(), metadata -> {
            Map<String, Object> changed = new HashMap<>(metadata);
            changed.put(SessionStore.COMPRESSED, "[removed]");
            return changed;
        });

        List<TurnMessage> read = store.messages(session.id());
        assertThat(read.get(0).id()).isEqualTo(stored.get(0).id());
        assertThat(read.get(0).text()).isEqualTo("a long listing");
        assertThat(read.get(0).metadata()).containsEntry(SessionStore.COMPRESSED, "[removed]")
                .containsEntry("callId", "c1");
        assertThat(read.get(1)).isEqualTo(stored.get(1));
    }

    @Test
    void a_message_appended_with_an_id_keeps_it_where_the_store_says_so() {
        SessionStore store = store();
        Session session = aSession("s1");
        store.create(session);

        TurnMessage stored = store.append(session.id(),
                List.of(TurnMessage.assistant("from before").stored("m-earlier"))).get(0);

        if (keepsAGivenId()) {
            assertThat(stored.id()).isEqualTo("m-earlier");
            assertThat(store.messages(session.id())).extracting(TurnMessage::id).containsExactly("m-earlier");
        } else {
            assertThat(stored.id()).isNotEqualTo("m-earlier");
            assertThat(store.messages(session.id())).extracting(TurnMessage::id).containsExactly(stored.id());
        }
    }

    @Test
    void a_session_that_is_not_there_has_no_messages_and_takes_none() {
        SessionStore store = store();
        SessionId missing = SessionId.of("nobody");

        assertThat(store.find(missing)).isEmpty();
        assertThat(store.messages(missing)).isEmpty();
        assertThatThrownBy(() -> store.append(missing, List.of(TurnMessage.assistant("lost"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.updateMessageMetadata(missing, "m1", metadata -> metadata))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.update(missing, s -> s)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_message_that_is_not_there_cannot_be_changed() {
        SessionStore store = store();
        Session session = aSession("s1");
        store.create(session);

        assertThatThrownBy(() -> store.updateMessageMetadata(session.id(), "no-such-message", metadata -> metadata))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
