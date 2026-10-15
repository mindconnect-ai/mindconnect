package ai.mindconnect.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailDraftTest {

    @Test
    void a_line_of_addresses_becomes_a_list_whichever_separator_was_used() {
        assertThat(MailDraft.split("a@x.de, b@x.de; c@x.de"))
                .containsExactly("a@x.de", "b@x.de", "c@x.de");
    }

    @Test
    void empty_entries_are_dropped_rather_than_sent_to() {
        assertThat(MailDraft.split("a@x.de,,  ,b@x.de")).containsExactly("a@x.de", "b@x.de");
        assertThat(MailDraft.split("   ")).isEmpty();
        assertThat(MailDraft.split(null)).isEmpty();
    }

    @Test
    void a_draft_normalises_what_it_was_given() {
        MailDraft draft = new MailDraft(List.of("  a@x.de  ", ""), null, "  Hello  ", null);

        assertThat(draft.to()).containsExactly("a@x.de");
        assertThat(draft.cc()).isEmpty();
        assertThat(draft.subject()).isEqualTo("Hello");
        assertThat(draft.body()).isEmpty();
    }

    @Test
    void a_rewrite_changes_the_body_and_nothing_else() {
        MailDraft draft = new MailDraft(List.of("a@x.de"), List.of("b@x.de"), "Re: offer", "old");

        MailDraft rewritten = draft.withBody("new");

        assertThat(rewritten.body()).isEqualTo("new");
        assertThat(rewritten.to()).isEqualTo(draft.to());
        assertThat(rewritten.cc()).isEqualTo(draft.cc());
        assertThat(rewritten.subject()).isEqualTo(draft.subject());
    }

    @Test
    void an_html_draft_keeps_its_words_beside_it_and_a_plain_rewrite_drops_the_html() {
        MailDraft draft = MailDraft.empty().withHtml("<p><b>Hi</b> Bob</p>", "Hi Bob");

        assertThat(draft.isHtml()).isTrue();
        assertThat(draft.body()).isEqualTo("Hi Bob");
        // The HTML would still say the old thing.
        assertThat(draft.withBody("Hello Bob").isHtml()).isFalse();
        assertThat(new MailDraft(List.of(), List.of(), "", "x", "  ").isHtml()).isFalse();
    }
}
