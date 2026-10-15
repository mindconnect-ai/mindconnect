package ai.mindconnect.agent.tools.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelevantExcerptsTest {

    /** A page whose answer sits at the very end — where a head-truncating reader never looks. */
    private static String pageWithAnswerAtTheEnd() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Widget 3000\n\n");
        for (int i = 0; i < 40; i++) {
            sb.append("## Section ").append(i).append("\n\n")
              .append("Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(12))
              .append("\n\n");
        }
        sb.append("## Ordering\n\nThe Widget 3000 costs CHF 249.00 including shipping.\n");
        return sb.toString();
    }

    @Test
    void keeps_the_passage_that_answers_even_when_it_sits_past_the_cut() {
        String page = pageWithAnswerAtTheEnd();
        assertThat(page.length()).isGreaterThan(20_000);

        // What the old behaviour would have handed the model.
        assertThat(DocumentParser.truncate(page)).doesNotContain("CHF 249.00");

        assertThat(RelevantExcerpts.select(page, "price of the widget")).contains("CHF 249.00");
    }

    @Test
    void never_exceeds_the_budget() {
        String selected = RelevantExcerpts.select(pageWithAnswerAtTheEnd(), "price of the widget", 3_000);
        assertThat(selected.length()).isLessThanOrEqualTo(3_000);
    }

    @Test
    void a_long_run_without_blank_lines_does_not_become_one_passage() {
        // A reference list: no blank lines for tens of thousands of characters.
        // Before the split-at-line-boundary guard this arrived as a single chunk
        // that outscored the article and blew the budget on its own.
        StringBuilder sb = new StringBuilder("# Article\n\nThe price is CHF 12.\n\n## References\n\n");
        for (int i = 0; i < 400; i++) {
            sb.append(i).append(". [A note about price and cost](https://example.com/ref/").append(i).append(")\n");
        }
        String selected = RelevantExcerpts.select(sb.toString(), "price", 2_000);
        assertThat(selected.length()).isLessThanOrEqualTo(2_000);
        assertThat(selected).contains("CHF 12");
    }

    @Test
    void prefers_prose_over_link_lists_carrying_the_same_terms() {
        StringBuilder sb = new StringBuilder("# Doc\n\n## Navigation\n\n");
        for (int i = 0; i < 60; i++) {
            sb.append("- [virtual threads guide ").append(i)
              .append("](https://example.com/virtual-threads/").append(i).append(")\n");
        }
        sb.append("\n\n## Explanation\n\n")
          .append("A virtual thread is scheduled by the JVM rather than the operating system, ")
          .append("which is what makes blocking cheap. ".repeat(20))
          .append("\n\n")
          .append("Filler paragraph without the topic. ".repeat(200));

        String selected = RelevantExcerpts.select(sb.toString(), "virtual threads", 2_000);
        assertThat(selected).contains("scheduled by the JVM");
    }

    @Test
    void short_pages_and_empty_queries_are_returned_untouched() {
        String page = "# Small\n\nNothing much here.";
        assertThat(RelevantExcerpts.select(page, "anything")).isEqualTo(page);

        String big = pageWithAnswerAtTheEnd();
        assertThat(RelevantExcerpts.select(big, "  ")).isEqualTo(DocumentParser.truncate(big, RelevantExcerpts.DEFAULT_BUDGET_CHARS));
    }

    @Test
    void german_compounds_are_matched_by_prefix() {
        String page = "# Shop\n\n" + "Fülltext ohne Bezug. ".repeat(600)
                + "\n\n## Angaben\n\nDer Preisvergleich zeigt CHF 88 als besten Wert.\n";
        assertThat(RelevantExcerpts.select(page, "Preis", 1_500)).contains("CHF 88");
    }
}
