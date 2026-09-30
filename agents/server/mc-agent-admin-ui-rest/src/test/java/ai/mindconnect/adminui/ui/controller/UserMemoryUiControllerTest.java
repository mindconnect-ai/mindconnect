package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.UserMemoryComponent;
import ai.mindconnect.adminui.ui.component.UserMemoryComponentAccess;
import ai.mindconnect.adminui.ui.component.UserMemoryComponent.Row;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryUserMemoryRepository;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.MemoryType;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile's "Memory" tab lists the signed-in user's entries — shared and
 * each agent's, the agent by name — and a user reaches only their own: a row
 * naming somebody else is answered like one that is gone.
 */
class UserMemoryUiControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private final UserMemoryService memory = new UserMemoryService(new InMemoryUserMemoryRepository());
    private final InMemoryAgentDefinitionRepository agents = new InMemoryAgentDefinitionRepository();
    private final UserMemoryUiController controller = new UserMemoryUiController(memory, agents);

    @Test
    void theTabListsTheUsersOwnEntriesWithTheAgentByName() throws Exception {
        AgentDefinition secretary = agents.save(AgentDefinition.create("secretary", "d", "p", null, "cfg"));
        memory.write(ALICE, "role", MemoryType.USER, "Head of purchasing", "Leads purchasing.", null);
        memory.write(ALICE, secretary.id(), "travel", MemoryType.PROJECT, "Books via Egencia", "c", null);
        memory.write(BOB, "bobs", MemoryType.USER, "Bob's own", "c", null);

        String tab = json(controller.tab(ALICE).orElseThrow());

        assertThat(tab).contains(UserMemoryComponent.PROFILE_TABLE_ID, "Head of purchasing", "Books via Egencia",
                "Shared by all agents", "Agent: secretary").doesNotContain("Bob's own");
    }

    @Test
    void viewOpensTheEntryInADialog() throws Exception {
        MemoryEntry entry = memory.write(ALICE, "role", MemoryType.USER, "Head of purchasing",
                "Leads **purchasing**.", null).entry();

        String patch = json(controller.view(user("alice"), Row.of(entry).id()));

        assertThat(patch).contains(UserMemoryComponent.DIALOG_ID, "Leads **purchasing**.");
    }

    @Test
    void deleteRemovesTheEntryAndRedrawsTheTable() throws Exception {
        AgentId secretary = AgentId.of("secretary");
        MemoryEntry entry = memory.write(ALICE, secretary, "travel", MemoryType.PROJECT, "Egencia", "c", null).entry();

        String patch = json(controller.delete(user("alice"), Row.of(entry).id()));

        assertThat(patch).contains("Memory deleted", UserMemoryComponent.PROFILE_TABLE_ID);
        assertThat(memory.read(ALICE, secretary, "travel")).isEmpty();
    }

    @Test
    void aRowOfSomebodyElseIsAnsweredLikeOneThatIsGone() throws Exception {
        MemoryEntry bobs = memory.write(BOB, "role", MemoryType.USER, "Bob's own", "c", null).entry();

        assertThat(json(controller.view(user("alice"), Row.of(bobs).id()))).contains("Not found")
                .doesNotContain("Bob's own");
        assertThat(json(controller.delete(user("alice"), Row.of(bobs).id()))).contains("Not found");
        assertThat(memory.read(BOB, "role")).as("Bob's entry is untouched").isPresent();
        assertThat(json(controller.delete(user("alice"), "not-a-row"))).contains("Not found");
    }

    @Test
    void aRowIdCarriesUserAgentAndNameThroughOnePathSegment() {
        Row row = new Row(UserId.of("alice@example.com"), AgentId.of("00000002-0000-0000-0000-000000000012"), "a-b");

        assertThat(row.id()).matches("[A-Za-z0-9_-]+");
        assertThat(Row.parse(row.id())).contains(row);
        assertThat(Row.parse(new Row(ALICE, null, "x").id())).get().extracting(Row::agentId).isNull();
    }

    @Test
    void aNoteKeepsItsLinesInTheDialog() {
        assertThat(UserMemoryComponentAccess.hardBreaks("Fact.\nWhy: asked.\n\nNext paragraph"))
                .isEqualTo("Fact.  \nWhy: asked.\n\nNext paragraph");
    }

    @Test
    void withoutTheMemoryThereIsNoTab() {
        assertThat(new UserMemoryUiController((UserMemoryService) null, null).tab(ALICE)).isEmpty();
    }

    static OidcUser user(String name) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id").subject("sub-" + name)
                .claim(StandardClaimNames.PREFERRED_USERNAME, name)
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken);
    }

    static String json(Object node) throws Exception {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(node);
    }
}
