package ai.mindconnect.user.adapter.tool;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AliasTool;
import ai.mindconnect.agent.tool.PinnedParamsTool;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.domain.UserToolId;
import ai.mindconnect.user.port.out.UserToolRepository;
import ai.mindconnect.user.service.UserToolService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a user keeps in their own account, laid over what the agent lists.
 *
 * <p>The case the whole layer exists for is
 * {@link #the_same_tool_twice_with_two_accounts}.
 */
class StoredUserToolRosterTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final AgentId ASSISTANT = AgentId.of("assistant");
    private static final AgentId RESEARCHER = AgentId.of("researcher");

    private final UserToolService service = new UserToolService(new MapUserTools());
    private final StoredUserToolRoster roster = new StoredUserToolRoster(service);

    /** A registry with one group, for the set rows. */
    private static final ai.mindconnect.agent.tool.ToolRegistry EMAIL_REGISTRY = new ai.mindconnect.agent.tool.ToolRegistry() {
        @Override public java.util.Optional<ai.mindconnect.agent.tool.Tool> resolve(AgentTool tool,
                ai.mindconnect.agent.tool.ToolCallScope scope) { return java.util.Optional.empty(); }
        @Override public java.util.Set<String> knownToolNames() {
            return java.util.Set.of("email_list_messages", "email_read_message", "email_send_message");
        }
        @Override public Map<String, java.util.Set<String>> toolNamesByGroup() {
            return Map.of("email", new java.util.LinkedHashSet<>(
                    List.of("email_list_messages", "email_read_message", "email_send_message")));
        }
    };

    @Test
    void a_set_row_is_every_tool_of_the_group_with_one_account_and_the_members_as_set() {
        StoredUserToolRoster withSets = new StoredUserToolRoster(service, () -> EMAIL_REGISTRY);
        service.add(ALICE, null, "group:email", null, null, Map.of("account", "arbeit"), null, Map.of(
                "email_read_message", new UserTool.Member(false, null),        // taken out
                "email_send_message", new UserTool.Member(true, true)));       // asks first

        List<AgentTool> refs = withSets.apply(ALICE, ASSISTANT, List.of());

        assertThat(refs).extracting(AgentTool::name).containsExactly("email_list_messages", "email_send_message");
        assertThat(refs).allSatisfy(ref -> assertThat(ref.overrides()).containsEntry(
                ai.mindconnect.agent.tool.PinnedParamsTool.OVERRIDE_KEY, Map.of("account", "arbeit")));
        assertThat(refs.get(0).needsApproval()).isFalse();
        assertThat(refs.get(1).needsApproval()).isTrue();
        // Two rows, two ids — and stable across calls.
        assertThat(refs).extracting(ref -> ref.id().value()).doesNotHaveDuplicates()
                .containsExactlyElementsOf(withSets.apply(ALICE, ASSISTANT, List.of()).stream()
                        .map(ref -> ref.id().value()).toList());
    }

    @Test
    void a_set_row_without_a_registry_to_expand_it_is_left_out_rather_than_guessed() {
        service.add(ALICE, null, "group:email", null, null, Map.of(), null);

        assertThat(roster.apply(ALICE, ASSISTANT, List.of(AgentTool.of("web_search"))))
                .extracting(AgentTool::name).containsExactly("web_search");
    }

    @Test
    void a_user_with_nothing_of_their_own_gets_the_agents_list_untouched() {
        List<AgentTool> agentRefs = List.of(AgentTool.of("web_search"));

        assertThat(roster.apply(ALICE, ASSISTANT, agentRefs)).isSameAs(agentRefs);
        assertThat(roster.apply(null, ASSISTANT, agentRefs)).isSameAs(agentRefs);
    }

    @Test
    void a_tool_the_agent_does_not_list_is_added() {
        service.add(ALICE, null, "email_list_messages", null, null, Map.of(), null);

        List<AgentTool> refs = roster.apply(ALICE, ASSISTANT, List.of(AgentTool.of("web_search")));

        assertThat(refs).extracting(AgentTool::name).containsExactly("web_search", "email_list_messages");
        // The agent's own tools stay in front: they are what it was built around.
        assertThat(refs.get(1).enabled()).isTrue();
    }

    @Test
    void the_same_tool_twice_with_two_accounts() {
        // The point of the layer: one tool name in the registry, two entries in
        // this user's chats, each pinned to one of their mailboxes.
        service.add(ALICE, null, "email_list_messages", "email_privat", "Reads my private mailbox",
                pin("privat"), null);
        service.add(ALICE, null, "email_list_messages", "email_arbeit", "Reads my work mailbox",
                pin("arbeit"), null);

        List<AgentTool> refs = roster.apply(ALICE, ASSISTANT, List.of());

        assertThat(refs).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("email_arbeit", "email_privat");
        assertThat(refs).allSatisfy(ref -> {
            // Both resolve the same registry tool …
            assertThat(ref.overrides()).containsEntry(AliasTool.OVERRIDE_KEY, "email_list_messages");
            assertThat(AliasTool.registryName(ref)).isEqualTo("email_list_messages");
        });
        // … and each one on its own account.
        assertThat(account(refs, "email_privat")).isEqualTo("privat");
        assertThat(account(refs, "email_arbeit")).isEqualTo("arbeit");
        assertThat(refs).extracting(AgentTool::description)
                .containsExactlyInAnyOrder("Reads my private mailbox", "Reads my work mailbox");
    }

    @Test
    void a_binding_without_a_name_of_its_own_changes_the_agents_entry_in_place() {
        service.add(ALICE, null, "email_list_messages", null, "Only my work mailbox", pin("arbeit"), null);

        List<AgentTool> refs = roster.apply(ALICE, ASSISTANT,
                List.of(AgentTool.of("email_list_messages", "Reads a mailbox")));

        assertThat(refs).singleElement().satisfies(ref -> {
            assertThat(ref.name()).isEqualTo("email_list_messages");
            assertThat(ref.description()).isEqualTo("Only my work mailbox");
            assertThat(ref.overrides()).doesNotContainKey(AliasTool.OVERRIDE_KEY);
        });
        assertThat(account(refs, "email_list_messages")).isEqualTo("arbeit");
    }

    @Test
    void the_users_pin_wins_over_the_agents_and_the_rest_of_it_survives() {
        service.add(ALICE, null, "email_list_messages", null, null, pin("arbeit"), null);
        AgentTool fromAgent = AgentTool.of("email_list_messages", "Reads a mailbox",
                Map.of("params", Map.of("account", "shared", "folder", "INBOX")));

        List<AgentTool> refs = roster.apply(ALICE, ASSISTANT, List.of(fromAgent));

        Object pinned = refs.get(0).overrides().get(PinnedParamsTool.OVERRIDE_KEY);
        assertThat(pinned).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("account", "arbeit")     // theirs wins
                .containsEntry("folder", "INBOX");      // the agent's other pin is left alone
    }

    @Test
    void a_tool_the_user_switched_off_leaves_their_chats() {
        var added = service.add(ALICE, null, "web_search", null, null, Map.of(), null);
        service.setEnabled(ALICE, added.id(), false);

        assertThat(roster.apply(ALICE, ASSISTANT, List.of(AgentTool.of("web_search")))).isEmpty();
    }

    @Test
    void an_approval_is_tightened_by_either_side_and_relaxed_by_neither() {
        service.add(ALICE, null, "bash", null, null, Map.of(), true);

        assertThat(roster.apply(ALICE, ASSISTANT, List.of(AgentTool.of("bash"))))
                .singleElement().satisfies(ref -> assertThat(ref.needsApproval()).isTrue());

        // And a user cannot take away one the agent already asks for.
        UserToolService other = new UserToolService(new MapUserTools());
        other.add(ALICE, null, "bash", null, null, Map.of(), false);
        AgentTool asking = new AgentTool(ai.mindconnect.agent.tool.AgentToolId.random(), "bash", null,
                Map.of(), true, false, true, null);

        assertThat(new StoredUserToolRoster(other).apply(ALICE, ASSISTANT, List.of(asking)))
                .singleElement().satisfies(ref -> assertThat(ref.needsApproval()).isTrue());
    }

    @Test
    void a_binding_for_one_agent_does_not_reach_another() {
        service.add(ALICE, RESEARCHER, "web_read", null, null, Map.of(), null);

        assertThat(roster.apply(ALICE, ASSISTANT, List.of())).isEmpty();
        assertThat(roster.apply(ALICE, RESEARCHER, List.of())).extracting(AgentTool::name)
                .containsExactly("web_read");
    }

    @Test
    void two_bindings_cannot_claim_one_name() {
        // Two tools of one name reach the model as one, and which runs would be
        // an accident of ordering.
        service.add(ALICE, null, "email_list_messages", "email_privat", null, pin("privat"), null);

        assertThatThrownBy(() -> service.add(ALICE, null, "email_read_message", "email_privat", null,
                pin("privat"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email_privat");
    }

    @Test
    void the_binding_id_is_stable_across_calls() {
        // It becomes the AgentTool's id, and a new one on every turn would make
        // anything keyed by it meaningless.
        service.add(ALICE, null, "web_search", null, null, Map.of(), null);

        assertThat(roster.apply(ALICE, ASSISTANT, List.of()).get(0).id())
                .isEqualTo(roster.apply(ALICE, ASSISTANT, List.of()).get(0).id());
    }

    /** The in-memory adapter lives in mc-user; this module's tests bring their own. */
    private static final class MapUserTools implements UserToolRepository {

        private final Map<UserToolId, UserTool> byId = new ConcurrentHashMap<>();

        @Override public void save(UserTool tool) { byId.put(tool.id(), tool); }

        @Override public Optional<UserTool> findById(UserToolId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override public List<UserTool> findByUser(ai.mindconnect.agent.UserId userId) {
            return byId.values().stream().filter(tool -> tool.userId().equals(userId)).toList();
        }

        @Override public void deleteById(UserToolId id) { byId.remove(id); }
    }

    private static Map<String, Object> pin(String account) {
        return Map.of("account", account);
    }

    private static String account(List<AgentTool> refs, String name) {
        AgentTool ref = refs.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
        return ref.overrides().get(PinnedParamsTool.OVERRIDE_KEY) instanceof Map<?, ?> pinned
                ? String.valueOf(pinned.get("account")) : null;
    }
}
