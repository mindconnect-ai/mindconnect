package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.management.RepositoryAgentManagement;
import ai.mindconnect.agent.management.RepositorySessionManagement;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the agent pages refuse: a name another agent has, and a session that is
 * not the caller's.
 */
class AgentUiControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final AgentDefinition SCOUT =
            new AgentDefinition("Scout", "A test agent", "prompt", ModelSettings.NONE, List.of(), 5, Map.of());
    private static final SessionId ALICES = SessionId.of("s1");

    private final ObjectMapper json = new ObjectMapper();
    private AgentManagement agents;
    private SessionManagement sessions;
    private ManagedAgent scout;
    private AgentUiController controller;

    @BeforeEach
    void setUp() {
        ScopeSupplier namespace = ScopeSupplier.fixed(new Namespace("test"));
        var agentRepository = new InMemoryAgentRepository(namespace);
        var sessionRepository = new InMemorySessionRepository(namespace);
        var messages = new InMemoryMessageRepository(ScopeSupplier.fixed(new Namespace("test")));
        agents = new RepositoryAgentManagement(agentRepository);
        sessions = new RepositorySessionManagement(sessionRepository, messages, agentRepository,
                SessionDirectoryPolicy.unrestricted(), List.of());
        scout = agents.create(ManagedAgent.of(SCOUT));
        new RepositorySessionStore(sessionRepository, messages)
                .create(new Session(ALICES, UserId.of("alice"), null, SCOUT, Map.of(), NOW));

        PluginRegistry registry = new DefaultPluginRegistry();
        registry.provide(AgentManagement.class, () -> agents);
        registry.provide(SessionManagement.class, () -> sessions);
        registry.start();
        var beans = new StaticListableBeanFactory();
        controller = new AgentUiController(new AgentRuntime(registry, () -> { }), null, null, null, json,
                beans.getBeanProvider(ai.mindconnect.agent.skill.port.out.SkillRepository.class),
                beans.getBeanProvider(MemoryAdminUiController.class));
    }

    @Test
    void aNameAnotherAgentHasIsAnsweredWithAToastAndCreatesNothing() throws Exception {
        ResponseEntity<?> response = controller.create(Map.of("name", "Scout"), user("alice"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json.writeValueAsString(response.getBody())).contains("There is already an agent named 'Scout'");
        assertThat(agents.list()).hasSize(1);
    }

    @Test
    void renamingAnAgentToATakenNameIsAnsweredWithAToastAndChangesNothing() throws Exception {
        ManagedAgent poet = agents.create(ManagedAgent.of(new AgentDefinition("Poet", "", "", ModelSettings.NONE,
                List.of(), 5, Map.of())));

        ResponseEntity<?> response = controller.update(poet.id().value(), Map.of("name", "Scout"), user("alice"));

        assertThat(json.writeValueAsString(response.getBody())).contains("There is already an agent named 'Scout'");
        assertThat(agents.find(poet.id())).get().extracting(a -> a.definition().name()).isEqualTo("Poet");
    }

    @Test
    void theFormSwitchesOffThatOtherAgentsMayCallTheAgent_andOnAgain() {
        controller.update(scout.id().value(), Map.of("name", "Scout", "callableByAgents", false), user("alice"));
        assertThat(agents.find(scout.id()).orElseThrow().definition().metadata())
                .containsEntry("callableByAgents", false);

        controller.update(scout.id().value(), Map.of("name", "Scout", "callableByAgents", true), user("alice"));
        assertThat(agents.find(scout.id()).orElseThrow().definition().metadata())
                .doesNotContainKey("callableByAgents");
    }

    @Test
    void theFormSetsHowMuchOfAToolResultTheModelIsShown_andBlankIsTheDefaultAgain() {
        controller.update(scout.id().value(), Map.of("name", "Scout", "maxToolResultChars", 5000), user("alice"));
        assertThat(agents.find(scout.id()).orElseThrow().definition().metadata())
                .containsEntry("maxResultChars", 5000);

        controller.update(scout.id().value(), Map.of("name", "Scout", "maxToolResultChars", ""), user("alice"));
        assertThat(agents.find(scout.id()).orElseThrow().definition().metadata())
                .doesNotContainKey("maxResultChars");
    }

    @Test
    void someoneElsesSessionIsNotDeleted() {
        ResponseEntity<?> response = controller.deleteSession(scout.id().value(), ALICES.value(), user("bob"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(sessions.find(ALICES)).isPresent();
    }

    @Test
    void theOwnerDeletesTheirSession() {
        ResponseEntity<?> response = controller.deleteSession(scout.id().value(), ALICES.value(), user("alice"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(sessions.find(ALICES)).isEmpty();
    }

    private static OidcUser user(String name) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id").subject("sub-" + name)
                .claim(StandardClaimNames.PREFERRED_USERNAME, name)
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken);
    }
}
