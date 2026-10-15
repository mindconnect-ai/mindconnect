package ai.mindconnect.mail.view;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The name of a view, and the state that goes with it. */
class ViewIdAndStateTest {

    @Test
    void a_folder_id_survives_a_slash_in_the_folder_name() {
        ViewId id = ViewId.folder("email.freemail", "Kunden/2026");

        assertThat(id.value()).isEqualTo("f/email.freemail/Kunden%2F2026");
        assertThat(id.isFolder()).isTrue();
        assertThat(id.account()).isEqualTo("email.freemail");
        assertThat(id.folderId()).isEqualTo("Kunden/2026");
        assertThat(ViewId.of(id.value())).isEqualTo(id);
    }

    @Test
    void the_other_two_spellings() {
        assertThat(ViewId.allInboxes().isAllInboxes()).isTrue();
        assertThat(ViewId.allInboxes().account()).isNull();
        ViewId saved = ViewId.saved();
        assertThat(saved.isSaved()).isTrue();
        assertThat(saved.value()).startsWith("s/");
        assertThat(ViewId.saved()).isNotEqualTo(saved);
    }

    @Test
    void state_is_changed_a_piece_at_a_time_and_a_search_starts_on_page_one() {
        ViewState s = ViewState.EMPTY.page(3).tick(List.of("a:1", "a:2"));

        assertThat(s.page()).isEqualTo(3);
        assertThat(s.selected()).containsExactlyInAnyOrder("a:1", "a:2");
        assertThat(s.untick(List.of("a:1")).selected()).containsExactly("a:2");
        assertThat(s.search("Swisscom").page()).isEqualTo(1);
        assertThat(s.search("  ").query()).isNull();
        assertThat(s.with("unreadOnly", "true").flag("unreadOnly")).isTrue();
        assertThat(s.with("unreadOnly", null).extra()).isEmpty();
    }

    @Test
    void ticks_follow_a_moved_message_to_its_new_name() {
        ViewState s = ViewState.EMPTY.select(Set.of("a:1", "a:2"));

        ViewState after = s.renamed(Map.of("a:1", "a:9"));

        assertThat(after.selected()).containsExactlyInAnyOrder("a:9", "a:2");
    }
}
