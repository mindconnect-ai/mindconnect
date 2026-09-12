package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.ImportReport;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

import java.util.Locale;

/**
 * One entry, and the decision: what it is, what it drags in, what it would do
 * to what is already here — and, after an import, what it actually did.
 *
 * <p>The warning above the buttons is the point of the screen. An imported
 * agent is a system prompt and a tool list that somebody else wrote, and it
 * runs on this installation's models with this installation's tools. That is
 * worth one sentence before the click, not a line in a log after it.
 */
final class RegistryEntryView {

    static final String STACK_ID = "registry-entry-stack";

    private final RegistrySource source;
    private final RegistryEntry entry;
    private final RegistryService.EntryStatus status;
    /** Set once an import has run — what happened, member by member. */
    private final ImportReport report;
    private final String error;

    RegistryEntryView(RegistrySource source, RegistryEntry entry,
                      RegistryService.EntryStatus status, ImportReport report, String error) {
        this.source = source;
        this.entry = entry;
        this.status = status;
        this.report = report;
        this.error = error;
    }

    UiNode render() {
        String base = RegistryListView.BASE + "/api/" + source.id().value();

        UiDetail detail = UiDetail.of("registry-entry", entry.name())
                .icon(RegistryBrowseView.iconFor(entry.type()));
        detail.field(UiField.text("type", "Kind", entry.type().label()));
        if (entry.version() != null) {
            detail.field(UiField.text("version", "Version", entry.version()));
        }
        if (entry.author() != null) {
            detail.field(UiField.text("author", "Author", entry.author()));
        }
        if (entry.description() != null) {
            detail.field(UiField.textarea("description", "Description", entry.description()));
        }
        if (!entry.tags().isEmpty()) {
            detail.field(UiField.text("tags", "Tags", String.join(", ", entry.tags())));
        }
        if (!entry.requires().isEmpty()) {
            detail.field(UiField.text("requires", "Also imports",
                            String.join(", ", entry.requires()))
                    .hint("Installed first, in this order."));
        }
        detail.field(UiField.text("origin", "From",
                source.coordinates() + " · " + entry.path()));
        if (entry.homepage() != null) {
            detail.field(UiField.text("homepage", "Homepage", entry.homepage()));
        }

        if (!status.installable()) {
            detail.field(UiField.text("state", "State",
                            "This installation cannot import " + entry.type().label().toLowerCase(Locale.ROOT) + "s")
                    .hint("No installer for this kind is wired in — the module that owns the "
                            + "entity contributes one."));
        } else {
            detail.action(UiAction.primary("import", status.present() ? "Import, keep mine" : "Import")
                    .icon("download")
                    .confirm(confirmation())
                    .dispatch("POST", base + "/import/" + entry.id() + "?mode=SKIP_EXISTING"));
            detail.action(UiAction.danger("overwrite", "Import and overwrite")
                    .icon("refresh")
                    .confirm("Replace what is here with '" + entry.name() + "' from "
                            + source.coordinates() + "? Entities of the same name are "
                            + "overwritten, keeping their local ids.")
                    .dispatch("POST", base + "/import/" + entry.id() + "?mode=OVERWRITE"));
        }
        detail.action(UiAction.secondary("back", "Back to " + source.name()).icon("back")
                .dispatch("GET", base));

        UiStack stack = UiStack.of(STACK_ID);
        stack.child(warning());
        stack.child(detail);
        if (error != null) {
            stack.child(note("registry-entry-error", "✗ " + error));
        }
        if (report != null) {
            stack.child(reportNode());
        }
        return stack;
    }

    private String confirmation() {
        return status.present()
                ? "Import '" + entry.name() + "' from " + source.coordinates()
                        + "? What is already here of the same name is kept."
                : "Import '" + entry.name() + "' from " + source.coordinates() + "?";
    }

    /** What importing somebody else's repository means, above the buttons. */
    private UiNode warning() {
        String text = switch (entry.type()) {
            case AGENT -> "An agent is a system prompt and a tool list somebody else wrote. "
                    + "Imported, it runs on this installation's models with this installation's "
                    + "tools. Read its prompt before you let it run.";
            case WORKFLOW -> "A workflow is executable: its steps call tools, agents and scripts "
                    + "on this installation. Read it before you run it.";
            case LLM_CONFIG -> "An LLM config names a provider and a base URL — the address this "
                    + "installation would send prompts to. An API key in the file is dropped on "
                    + "import; set your own, or point the config at a ${ENV_VAR}.";
            case PACKAGE -> "A package installs several entities at once — agents with their "
                    + "prompts, workflows that run, the LLM configs they point at. Nothing is "
                    + "run by importing it, but everything in it is here afterwards.";
        };
        String origin = source.repositoryUrl() != null ? source.repositoryUrl() : source.coordinates();
        return note("registry-entry-warning", text + "\nFrom " + origin + ".");
    }

    private UiNode reportNode() {
        StringBuilder text = new StringBuilder(report.ok() ? "✓ " : "⚠ ")
                .append(report.summary());
        for (ImportedItem item : report.items()) {
            text.append('\n').append(item);
        }
        return note("registry-entry-report", text.toString());
    }

    private static UiNode note(String id, String text) {
        return UiStack.of(id)
                .<UiStack>withCssClass("llm-test-result")
                .child(UiText.of(id + "-text", text).<UiText>withCssClass("llm-test-body"));
    }
}
