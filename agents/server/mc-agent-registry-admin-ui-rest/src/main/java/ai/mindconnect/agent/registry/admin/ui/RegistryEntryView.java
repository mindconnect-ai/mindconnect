package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.ImportReport;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiSection;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One entry, and the decision: what it is, what it drags in, what it would do
 * to what is already here — and, after an import, what it actually did.
 *
 * <p>The warning above the buttons is the point of the screen. An imported
 * agent is a system prompt and a tool list that somebody else wrote, and it
 * runs on this installation's models with this installation's tools. That is
 * worth one sentence before the click, not a line in a log after it.
 *
 * <p>A package is two tabs, like an agent's detail page: its details, and the
 * entries it installs, filed under their kinds the way the registry lists
 * them. The import buttons then sit in the bar above the tabs, so they are
 * there whichever tab is open.
 */
final class RegistryEntryView {

    static final String STACK_ID = "registry-entry-stack";
    /** The Contents tab's container — its checkboxes are what the import buttons send. */
    static final String SELECTION_ID = "registry-package-selection";
    /** Checkbox name prefix; the rest of the name is the entry id. */
    static final String INCLUDE_PREFIX = "include-";
    /** How many users a "used by" line names before it counts the rest. */
    private static final int USED_BY_SHOWN = 3;

    private final RegistrySource source;
    private final RegistryIndex index;
    private final RegistryEntry entry;
    /** What the installation would do with an entry — this one, or a package's member. */
    private final Function<RegistryEntry, RegistryService.EntryStatus> status;
    /** What a package's import installs; null for any other kind, or when it could not be read. */
    private final RegistryService.PackageContents contents;
    /** Why a package's contents could not be read. */
    private final String contentsError;
    /**
     * Entry ids unticked in the last import or removal, so a re-rendered tab
     * keeps the choice; {@code null} when nobody has chosen yet, and then what
     * something outside the package uses starts unticked.
     */
    private final Set<String> excluded;
    /** Set once an import has run — what happened, member by member. */
    private final ImportReport report;
    private final String error;

    RegistryEntryView(RegistrySource source, RegistryIndex index, RegistryEntry entry,
                      Function<RegistryEntry, RegistryService.EntryStatus> status,
                      RegistryService.PackageContents contents, String contentsError,
                      Set<String> excluded, ImportReport report, String error) {
        this.source = source;
        this.index = index;
        this.entry = entry;
        this.status = status;
        this.contents = contents;
        this.contentsError = contentsError;
        this.excluded = excluded == null ? null : Set.copyOf(excluded);
        this.report = report;
        this.error = error;
    }

    UiNode render() {
        String base = RegistryListView.BASE + "/api/" + source.id().value();
        UiDetail detail = details();
        List<UiAction> actions = actions(base);

        UiStack stack = UiStack.of(STACK_ID);
        stack.child(warning());
        if (entry.type() == RegistryItemType.PACKAGE) {
            // The section's own title is escaped text and cannot carry an icon;
            // a header-only list draws the bar with one, as on the agent page.
            UiList header = UiList.of("registry-entry-header", entry.name())
                    .icon(RegistryBrowseView.iconFor(entry.type()));
            actions.forEach(header::action);
            stack.child(header);
            stack.child(UiSection.of("registry-entry-tabs", null)
                    .section("details", "Details", detail)
                    .section("contents", contentsTitle(), contentsList(base)));
        } else {
            actions.forEach(detail::action);
            stack.child(detail);
        }
        if (error != null) {
            stack.child(note("registry-entry-error", "✗ " + error));
        }
        if (report != null) {
            stack.child(reportNode());
        }
        return stack;
    }

    private String contentsTitle() {
        return contents == null ? "Contents" : "Contents (" + contents.members().size() + ")";
    }

    /**
     * Everything the package's import would install, by kind, each with
     * whether it is already here and an Include checkbox. The container's id is
     * the payload of the Import and Remove buttons, so the checkboxes travel
     * with the click from either tab — a hidden tab stays in the page.
     */
    private UiStack contentsList(String base) {
        UiList list = UiList.of("registry-package-contents", "");
        UiStack selection = UiStack.of(SELECTION_ID);
        if (contents != null && !contents.members().isEmpty()) {
            selection.child(UiText.of("registry-package-legend",
                            "Import installs the included entries that are new; Import and overwrite "
                                    + "also replaces those already here; Remove deletes the included "
                                    + "entries that are here. What is not included is left alone — and "
                                    + "what something outside the package still uses starts not included.")
                    .<UiText>withCssClass("registry-package-legend"));
        }
        selection.child(list);
        if (contents == null) {
            list.item(UiList.Item.of("contents-unreadable", "The package manifest could not be read")
                    .description(contentsError != null ? contentsError : entry.path()));
            return selection;
        }
        if (contents.members().isEmpty() && contents.unresolved().isEmpty()) {
            list.item(UiList.Item.of("contents-empty", "The package lists nothing"));
            return selection;
        }
        // A package is a handful of entries and this tab exists to show them,
        // so the rubrics start open.
        RegistryBrowseView.addGrouped(list, "registry-package-group-", contents.members(), true,
                member -> member(base, member));
        for (String missing : contents.unresolved()) {
            list.item(UiList.Item.of("unresolved-" + missing, missing)
                    .icon("warning")
                    .description("Named by the package, but not in this registry's index — "
                            + "an import skips it"));
        }
        return selection;
    }

    private UiList.Item member(String base, RegistryEntry member) {
        UiList.Item item = UiList.Item.of("member-" + member.id(), member.name())
                .icon(RegistryBrowseView.iconFor(member.type()));
        // Members written into the manifest itself have no entry page to open.
        if (index.find(member.id()).isPresent()) {
            item.dispatch("GET", base + "/entry/" + member.id());
        }
        RegistryService.EntryStatus memberStatus = status.apply(member);
        String state;
        String stateClass;
        if (!memberStatus.installable()) {
            state = "This installation cannot import this kind";
            stateClass = "registry-member-state registry-member-state--unavailable";
        } else if (memberStatus.present()) {
            state = "Already here";
            stateClass = "registry-member-state registry-member-state--present";
        } else {
            state = "New";
            stateClass = "registry-member-state registry-member-state--new";
        }
        List<String> usedBy = contents.usedBy(member.id());
        UiStack body = UiStack.of("member-body-" + member.id())
                .<UiStack>withCssClass("registry-member-body");
        if (memberStatus.installable()) {
            // Not inside the label: that is the link to the entry, and a click
            // on the box would follow it. The stylesheet lifts the wrapper into
            // the row's top right corner, beside the name.
            boolean included = excluded == null ? usedBy.isEmpty() : !excluded.contains(member.id());
            body.child(UiStack.of("member-include-" + member.id())
                    .<UiStack>withCssClass("registry-member-include")
                    .child(UiField.bool(INCLUDE_PREFIX + member.id(), "Include", included).asEditable()));
        }
        body.child(UiText.of("member-state-" + member.id(), state).<UiText>withCssClass(stateClass));
        if (!usedBy.isEmpty()) {
            body.child(UiText.of("member-used-by-" + member.id(), usedByLine(usedBy))
                    .<UiText>withCssClass("registry-member-used-by"));
        }
        body.child(UiText.of("member-desc-" + member.id(),
                        member.subtitle() + RegistryBrowseView.shortDescription(member))
                .<UiText>withCssClass("registry-member-desc"));
        return item.content(body);
    }

    /** "Used by Agent 'a', Agent 'b' and 14 more" — the first few, then a count. */
    static String usedByLine(List<String> users) {
        int shown = Math.min(users.size(), USED_BY_SHOWN);
        String line = "Used by " + String.join(", ", users.subList(0, shown));
        return users.size() > shown ? line + " and " + (users.size() - shown) + " more" : line;
    }

    /** The entry ids left unticked in a submitted Contents tab. */
    static Set<String> excludedFrom(Map<String, Object> body) {
        Set<String> excluded = new LinkedHashSet<>();
        if (body == null) {
            return excluded;
        }
        body.forEach((key, value) -> {
            if (key.startsWith(INCLUDE_PREFIX) && Boolean.FALSE.equals(asBoolean(value))) {
                excluded.add(key.substring(INCLUDE_PREFIX.length()));
            }
        });
        return excluded;
    }

    /** A checkbox arrives as a boolean from the page and as text from a plain form post. */
    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return null;
        String text = value.toString().strip();
        return text.equalsIgnoreCase("true") || text.equalsIgnoreCase("on") ? Boolean.TRUE
                : text.equalsIgnoreCase("false") ? Boolean.FALSE : null;
    }

    private List<UiAction> actions(String base) {
        List<UiAction> actions = new ArrayList<>();
        RegistryService.EntryStatus entryStatus = status.apply(entry);
        // A package's buttons carry the Contents tab's checkboxes along.
        String payload = entry.type() == RegistryItemType.PACKAGE ? SELECTION_ID : null;
        if (entryStatus.installable()) {
            actions.add(UiAction.primary("import", entryStatus.present() ? "Import, keep mine" : "Import")
                    .icon("download")
                    .confirm(confirmation(entryStatus))
                    .onClick(UiTrigger.api("POST",
                            base + "/import/" + entry.id() + "?mode=SKIP_EXISTING", payload)));
            actions.add(UiAction.danger("overwrite", "Import and overwrite")
                    .icon("refresh")
                    .confirm("Replace what is here with '" + entry.name() + "' from "
                            + source.coordinates() + "? Entities of the same name are "
                            + "overwritten, keeping their local ids.")
                    .onClick(UiTrigger.api("POST",
                            base + "/import/" + entry.id() + "?mode=OVERWRITE", payload)));
        }
        if (entry.type() == RegistryItemType.PACKAGE) {
            actions.add(UiAction.danger("remove", "Remove")
                    .icon("delete")
                    .confirm("Delete the included entries of '" + entry.name() + "' from this "
                            + "installation? Entries that are not included stay. Anything else "
                            + "that uses a deleted agent, workflow or LLM config stops working.")
                    .onClick(UiTrigger.api("POST", base + "/remove/" + entry.id(), payload)));
        }
        actions.add(UiAction.secondary("back", "Back to " + source.name()).icon("back")
                .dispatch("GET", base));
        return actions;
    }

    private UiDetail details() {
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

        if (!status.apply(entry).installable()) {
            detail.field(UiField.text("state", "State",
                            "This installation cannot import " + entry.type().label().toLowerCase(Locale.ROOT) + "s")
                    .hint("No installer for this kind is wired in — the module that owns the "
                            + "entity contributes one."));
        }
        return detail;
    }

    private String confirmation(RegistryService.EntryStatus entryStatus) {
        return entryStatus.present()
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
