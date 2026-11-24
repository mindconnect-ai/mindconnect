package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.RepositorySessionManagement;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.ManagedMessage;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.engine.adapter.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.chatui.service.SessionOwnership;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole of a tool result is downloaded by the chat's owner, and by
 * nobody else; what is no tool result is not handed out this way.
 */
class ToolResultDownloadControllerTest {

    private static final SessionId ALICES = SessionId.of("s1");
    private static final String LONG = "line\n".repeat(50_000);

    private ToolResultDownloadController controller;
    private String question;
    private String result;

    @BeforeEach
    void setUp() {
        var stored = new InMemorySessionRepository(NamespaceProvider.fixed("test"));
        var messages = new InMemoryMessageRepository(ScopeSupplier.fixed(new Namespace("test")));
        SessionManagement management = new RepositorySessionManagement(stored, messages,
                new InMemoryAgentRepository(NamespaceProvider.fixed("test")), SessionDirectoryPolicy.unrestricted(),
                List.of());
        var store = new RepositorySessionStore(stored, messages);
        store.create(new Session(ALICES, UserId.of("alice"), null,
                new AgentDefinition("helper", "Helps.", "You help.", ModelSettings.NONE, List.of(), 5, Map.of()),
                Map.of(), Instant.parse("2026-11-01T00:00:00Z")));
        store.append(ALICES, List.of(TurnMessage.user(List.of(new ai.mindconnect.agent.loop.domain.ContentPart.Text("list them"))),
                TurnMessage.toolResult("c1", "bash", LONG, false)));
        List<ManagedMessage> kept = management.messages(ALICES);
        question = kept.get(0).id();
        result = kept.get(1).id();

        PluginRegistry registry = new DefaultPluginRegistry();
        registry.provide(SessionManagement.class, () -> management);
        registry.start();
        AgentRuntime runtime = new AgentRuntime(registry, () -> { });
        controller = new ToolResultDownloadController(runtime, new SessionOwnership(runtime));
    }

    @Test
    void theOwnerDownloadsTheWholeResultAsAFileNamedAfterTheTool() {
        ResponseEntity<byte[]> response = controller.download(ALICES.value(), result, user("alice"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(LONG);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .startsWith("attachment; filename=\"bash-result-").endsWith(".txt\"");
    }

    @Test
    void someoneElsesResultAndWhatIsNoResultAreNotFound() {
        assertThat(controller.download(ALICES.value(), result, user("bob")).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.download(ALICES.value(), question, user("alice")).getStatusCode().value())
                .isEqualTo(404);
        assertThat(controller.download(ALICES.value(), "nowhere", user("alice")).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void theAddressNamesSessionAndMessage_andThereIsNoneWithoutAnId() {
        assertThat(ToolResultDownloadController.url(SessionId.of("s1"), "m7"))
                .isEqualTo("/chat/api/sessions/s1/tool-results/m7");
        assertThat(ToolResultDownloadController.url(SessionId.of("s1"), null)).isNull();
    }

    private static OidcUser user(String name) {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        OidcIdToken token = OidcIdToken.withTokenValue("id").subject("sub-" + name)
                .claim(StandardClaimNames.PREFERRED_USERNAME, name)
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), token,
                StandardClaimNames.PREFERRED_USERNAME);
    }
}
