package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.ImportReport;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.ui.model.UiNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry screens file entries under their kind, and a package shows what
 * it contains on a tab of its own.
 */
class RegistryViewsTest {

    private static final RegistrySource SOURCE = RegistrySource.of("acme/registry");
    private static final RegistryService.EntryStatus NEW = new RegistryService.EntryStatus(true, false);
    private static final RegistryService.EntryStatus HERE = new RegistryService.EntryStatus(true, true);

    private static final RegistryEntry WORKFLOW = entry("greeting", RegistryItemType.WORKFLOW);
    private static final RegistryEntry AGENT = entry("researcher", RegistryItemType.AGENT);
    private static final RegistryEntry CONFIG = entry("default-llm", RegistryItemType.LLM_CONFIG);
    private static final RegistryEntry KIT = entry("kit", RegistryItemType.PACKAGE);
    private static final RegistryEntry OTHER_AGENT = entry("writer", RegistryItemType.AGENT);

    private static final RegistryIndex INDEX = new RegistryIndex(1, "Acme", null,
            List.of(WORKFLOW, AGENT, CONFIG, KIT, OTHER_AGENT));

    @Test
    void the_registry_lists_its_entries_under_one_rubric_per_kind_in_a_fixed_order() throws Exception {
        String json = json(new RegistryBrowseView(SOURCE, INDEX, INDEX.entries(), null, null, e -> NEW)
                .render());

        assertThat(json).containsSubsequence("Agents  (2)", "LLM configs  (1)", "Workflows  (1)", "Packages  (1)");
        // Inside a rubric, the index's order.
        assertThat(json).containsSubsequence("\"researcher\"", "\"writer\"");
    }

    @Test
    void a_kind_with_nothing_in_it_gets_no_rubric() throws Exception {
        String json = json(new RegistryBrowseView(SOURCE, INDEX, List.of(AGENT), "res", null, e -> NEW)
                .render());

        assertThat(json).contains("Agents  (1)").doesNotContain("Workflows").doesNotContain("Packages");
    }

    @Test
    void a_package_has_a_details_tab_and_a_tab_with_its_members_by_kind() throws Exception {
        RegistryService.PackageContents contents = new RegistryService.PackageContents(
                List.of(WORKFLOW, AGENT), List.of("gone"));

        String json = json(new RegistryEntryView(SOURCE, INDEX, KIT, e -> NEW, contents, null, Set.of(), null, null)
                .render());

        assertThat(json).contains("registry-entry-tabs").contains("\"Details\"").contains("\"Contents (2)\"");
        assertThat(json).containsSubsequence("Agents  (1)", "Workflows  (1)");
        assertThat(json).contains("/registry/api/" + SOURCE.id().value() + "/entry/greeting");
        assertThat(json).contains("\"gone\"");
    }

    @Test
    void each_member_says_whether_it_is_already_here_and_carries_an_include_box_ticked_unless_left_out()
            throws Exception {
        RegistryService.PackageContents contents = new RegistryService.PackageContents(
                List.of(CONFIG, AGENT), List.of());
        RegistryService.EntryStatus here = new RegistryService.EntryStatus(true, true);

        UiNode page = new RegistryEntryView(SOURCE, INDEX, KIT, e -> e == CONFIG ? here : NEW,
                contents, null, Set.of("researcher"), null, null).render();
        JsonNode tree = new ObjectMapper().valueToTree(page);

        assertThat(tree.toString()).contains("\"Already here\"").contains("\"New\"");
        assertThat(node(tree, "include-default-llm").path("value").asBoolean()).isTrue();
        assertThat(node(tree, "include-researcher").path("value").asBoolean()).isFalse();
        // Import, overwrite and remove all send the Contents tab along.
        assertThat(tree.toString()).contains("/remove/kit")
                .contains("\"payload\":\"" + RegistryEntryView.SELECTION_ID + "\"");
    }

    @Test
    void what_something_else_uses_starts_unticked_until_somebody_chooses() throws Exception {
        RegistryService.EntryStatus here = new RegistryService.EntryStatus(true, true);
        RegistryService.PackageContents contents = new RegistryService.PackageContents(
                List.of(CONFIG, AGENT), List.of(),
                Map.of("default-llm", List.of("Agent 'default-chat'", "Agent 'planner'")));

        JsonNode fresh = new ObjectMapper().valueToTree(new RegistryEntryView(SOURCE, INDEX, KIT,
                e -> here, contents, null, null, null, null).render());
        JsonNode chosen = new ObjectMapper().valueToTree(new RegistryEntryView(SOURCE, INDEX, KIT,
                e -> here, contents, null, Set.of(), null, null).render());

        assertThat(fresh.toString()).contains("Used by Agent 'default-chat', Agent 'planner'");
        assertThat(node(fresh, "include-default-llm").path("value").asBoolean()).isFalse();
        assertThat(node(fresh, "include-researcher").path("value").asBoolean()).isTrue();
        // Once a choice came back from the page, it is the choice.
        assertThat(node(chosen, "include-default-llm").path("value").asBoolean()).isTrue();
    }

    @Test
    void a_long_used_by_list_names_a_few_and_counts_the_rest() {
        assertThat(RegistryEntryView.usedByLine(List.of("A", "B")))
                .isEqualTo("Used by A, B");
        assertThat(RegistryEntryView.usedByLine(List.of("A", "B", "C", "D", "E")))
                .isEqualTo("Used by A, B, C and 2 more");
    }

    @Test
    void only_unticked_boxes_leave_an_entry_out() {
        Map<String, Object> body = new HashMap<>();
        body.put("include-default-llm", true);
        body.put("include-researcher", false);
        body.put("include-writer", "false");
        body.put("unrelated", false);

        assertThat(RegistryEntryView.excludedFrom(body)).containsExactlyInAnyOrder("researcher", "writer");
        assertThat(RegistryEntryView.excludedFrom(null)).isEmpty();
    }

    @Test
    void a_package_whose_manifest_cannot_be_read_still_offers_the_import() throws Exception {
        String json = json(new RegistryEntryView(SOURCE, INDEX, KIT, e -> NEW, null, "404 Not Found", Set.of(), null, null)
                .render());

        assertThat(json).contains("\"Contents\"").contains("404 Not Found").contains("?mode=SKIP_EXISTING");
    }

    @Test
    void any_other_kind_stays_a_single_detail_without_tabs() throws Exception {
        String json = json(new RegistryEntryView(SOURCE, INDEX, AGENT, e -> NEW, null, null, Set.of(), null, null)
                .render());

        assertThat(json).doesNotContain("registry-entry-tabs").doesNotContain(RegistryEntryView.SELECTION_ID)
                .doesNotContain("/remove/")
                .contains("?mode=SKIP_EXISTING");
    }

    @Test
    void a_registry_row_offers_import_for_what_is_new_and_overwrite_for_what_is_here() throws Exception {
        String json = json(new RegistryBrowseView(SOURCE, INDEX, List.of(AGENT, CONFIG), null, null,
                e -> e == AGENT ? HERE : NEW).render());

        assertThat(json).contains("\"overwrite-researcher\"").contains("\"Overwrite\"")
                .contains("\"import-default-llm\"").doesNotContain("Re-import");
    }

    @Test
    void a_row_button_imports_from_the_list_and_sends_the_search_along() throws Exception {
        String base = "/registry/api/" + SOURCE.id().value();

        String json = json(new RegistryBrowseView(SOURCE, INDEX, List.of(AGENT, CONFIG), "res",
                RegistryItemType.AGENT, e -> e == AGENT ? HERE : NEW).render());

        // The import runs from the row, not after a detour via the entry page.
        assertThat(json).contains(base + "/install/default-llm?mode=SKIP_EXISTING")
                .contains(base + "/install/researcher?mode=OVERWRITE")
                .contains("\"payload\":\"registry-browse-search\"");
        // Overwriting asks first; adding what is new does not.
        assertThat(json).contains("Replace what is here with 'researcher'");
        // The row itself still opens the entry.
        assertThat(json).contains(base + "/entry/researcher");
    }

    @Test
    void a_row_wears_its_state_as_a_badge_beside_the_name() throws Exception {
        RegistryService.EntryStatus foreign = new RegistryService.EntryStatus(false, false);

        String json = json(new RegistryBrowseView(SOURCE, INDEX, List.of(AGENT, CONFIG, WORKFLOW), null, null,
                e -> e == AGENT ? HERE : e == WORKFLOW ? foreign : NEW).render());

        assertThat(json).contains("\"Already here\"").contains("registry-entry-badge--present")
                .contains("\"Not installable here\"").contains("registry-entry-badge--unavailable");
        // A kind with no installer has no button to press.
        assertThat(json).doesNotContain("/install/greeting");
    }

    @Test
    void what_the_import_did_stands_above_the_list_it_came_back_to() throws Exception {
        ImportReport report = new ImportReport(SOURCE.id().value(), "kit", List.of(
                ImportedItem.imported(AGENT, "researcher"),
                ImportedItem.failed(CONFIG, "does not parse")));

        String json = json(new RegistryBrowseView(SOURCE, INDEX, INDEX.entries(), null, null,
                e -> NEW, outcome(report)).render());

        assertThat(json).containsSubsequence("registry-browse-report", "registry-entries");
        assertThat(json).contains("⚠ kit — 1 imported, 1 failed")
                .contains("LLM config 'default-llm' — failed (does not parse)");
    }

    @Test
    void an_import_opens_the_rubrics_it_touched_and_leaves_the_rest_folded() throws Exception {
        ImportReport report = new ImportReport(SOURCE.id().value(), "greeting",
                List.of(ImportedItem.imported(WORKFLOW, "greeting")));

        JsonNode plain = new ObjectMapper().valueToTree(new RegistryBrowseView(SOURCE, INDEX,
                INDEX.entries(), null, null, e -> NEW).render());
        JsonNode after = new ObjectMapper().valueToTree(new RegistryBrowseView(SOURCE, INDEX,
                INDEX.entries(), null, null, e -> NEW, outcome(report)).render());

        assertThat(node(plain, "registry-group-workflow").path("collapseOpen").asBoolean()).isFalse();
        assertThat(node(after, "registry-group-workflow").path("collapseOpen").asBoolean()).isTrue();
        // The registry around it stays the map it was.
        assertThat(node(after, "registry-group-agent").path("collapseOpen").asBoolean()).isFalse();
    }

    @Test
    void an_import_that_could_not_run_says_so_above_the_list() throws Exception {
        String json = json(new RegistryBrowseView(SOURCE, INDEX, INDEX.entries(), null, null,
                e -> NEW, new RegistryOutcome("kit", null, null, "404 Not Found")).render());

        assertThat(json).containsSubsequence("✗ 404 Not Found", "registry-entries");
    }

    @Test
    void a_rubric_offers_to_import_what_is_missing_in_it_and_says_how_many() throws Exception {
        // One agent here, one not; every LLM config here.
        String json = json(new RegistryBrowseView(SOURCE, INDEX, INDEX.entries(), null, null,
                e -> e == AGENT || e.type() == RegistryItemType.LLM_CONFIG ? HERE : NEW).render());

        assertThat(json).contains("\"Import missing (1)\"")
                .contains("/install-group/agent")
                .contains("Import the 1 entry of Agents in acme/registry@main that are not here yet?");
        // A rubric with nothing missing keeps quiet, and so does Packages —
        // whether a package is here is not a thing an installation knows.
        assertThat(json).doesNotContain("/install-group/llm-config")
                .doesNotContain("/install-group/package");
    }

    @Test
    void the_rubric_button_counts_only_what_this_installation_could_install() throws Exception {
        RegistryService.EntryStatus foreign = new RegistryService.EntryStatus(false, false);

        String json = json(new RegistryBrowseView(SOURCE, INDEX, INDEX.entries(), null, null,
                e -> e == AGENT ? foreign : NEW).render());

        // The rubric holds two agents; one of them this installation has no
        // installer for, so the button offers the one it can actually install.
        assertThat(json).contains("Agents  (2)")
                .contains("\"import-missing-agent\"").contains("\"Import missing (1)\"");
    }

    @Test
    void importing_a_whole_rubric_is_filed_under_its_title() throws Exception {
        ImportReport report = new ImportReport(SOURCE.id().value(), null, List.of(
                ImportedItem.imported(AGENT, "researcher"),
                ImportedItem.imported(OTHER_AGENT, "writer")));

        JsonNode tree = new ObjectMapper().valueToTree(new RegistryBrowseView(SOURCE, INDEX,
                INDEX.entries(), null, null, e -> NEW,
                new RegistryOutcome("Agents", RegistryItemType.AGENT, report, null)).render());

        assertThat(tree.toString()).contains("✓ Agents — 2 imported");
        assertThat(node(tree, "registry-group-agent").path("collapseOpen").asBoolean()).isTrue();
    }

    /** An import of one entry, as the controller files it. */
    private static RegistryOutcome outcome(ImportReport report) {
        return new RegistryOutcome("kit", RegistryItemType.PACKAGE, report, null);
    }

    /** The one node with that id, wherever it sits in the tree. */
    private static JsonNode node(JsonNode tree, String id) {
        List<JsonNode> found = tree.findParents("id").stream()
                .filter(n -> id.equals(n.path("id").asText()))
                .toList();
        assertThat(found).as("node " + id).hasSize(1);
        return found.get(0);
    }

    private static RegistryEntry entry(String id, RegistryItemType type) {
        return new RegistryEntry(id, type, id, null, null, id + ".json", List.of(), null, null, List.of());
    }

    private static String json(UiNode node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }
}
