package ai.mindconnect.mail.imap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MailTextTest {

    @Test
    void html_becomes_the_words_it_was_wrapping() {
        String html = "<html><head><style>p{color:red}</style></head>"
                + "<body><p>Hello <b>Alice</b>,</p><p>the invoice is attached.</p></body></html>";

        assertThat(MailText.stripHtml(html)).isEqualTo("Hello Alice ,\n\nthe invoice is attached.");
    }

    @Test
    void a_script_block_is_dropped_with_its_content() {
        assertThat(MailText.stripHtml("<div>Hi<script>alert('x')</script></div>")).isEqualTo("Hi");
    }

    @Test
    void line_breaks_survive_and_blank_runs_collapse() {
        assertThat(MailText.stripHtml("a<br><br><br><br>b")).isEqualTo("a\n\nb");
    }

    @Test
    void the_common_entities_come_back_as_characters() {
        assertThat(MailText.stripHtml("a &amp; b &lt;c&gt; &quot;d&quot;")).isEqualTo("a & b <c> \"d\"");
    }

    @Test
    void nothing_in_makes_nothing_out() {
        assertThat(MailText.stripHtml(null)).isEmpty();
        assertThat(MailText.stripHtml("   ")).isEmpty();
    }

    @Test
    void text_that_fits_is_returned_untouched() {
        assertThat(MailText.truncate("short", 100)).isEqualTo("short");
        assertThat(MailText.truncate(null, 100)).isNull();
    }

    @Test
    void text_that_is_cut_says_so_and_says_how_to_get_the_rest() {
        String cut = MailText.truncate("abcdefghij", 4);

        assertThat(cut).startsWith("abcd").contains("cut after 4 characters of 10").contains("max_chars");
    }
}
