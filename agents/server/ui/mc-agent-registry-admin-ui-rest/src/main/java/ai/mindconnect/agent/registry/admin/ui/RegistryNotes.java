package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.ImportReport;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

/**
 * The block an import leaves behind: what happened, line by line, in the
 * screen's quiet result box.
 *
 * <p>Shared by the two screens that run an import — the entry page and the
 * catalog's row buttons — because an import reads the same wherever it was
 * started from.
 */
final class RegistryNotes {

    private RegistryNotes() {
    }

    /** A boxed note: one id, one body of text, newlines kept. */
    static UiNode note(String id, String text) {
        return UiStack.of(id)
                .<UiStack>withCssClass("llm-test-result")
                .child(UiText.of(id + "-text", text).<UiText>withCssClass("llm-test-body"));
    }

    /**
     * An import's report as a note: {@code headline} — what was asked for —
     * with the tick or the warning sign the summary earns, then every line the
     * walk produced.
     */
    static UiNode report(String id, String headline, ImportReport report) {
        StringBuilder text = new StringBuilder(report.ok() ? "✓ " : "⚠ ");
        if (headline != null && !headline.isBlank()) {
            text.append(headline).append(" — ");
        }
        text.append(report.summary());
        for (ImportedItem item : report.items()) {
            text.append('\n').append(item);
        }
        return note(id, text.toString());
    }
}
