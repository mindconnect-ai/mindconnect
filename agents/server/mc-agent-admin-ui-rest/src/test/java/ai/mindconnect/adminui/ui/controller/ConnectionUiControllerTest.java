package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.setup.ToolConnections;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Acquisition;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.credentials.adapter.memory.InMemoryConnectionRepository;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.OAuth2UserCreds;
import ai.mindconnect.credentials.service.ConnectionService;
import ai.mindconnect.schema.Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Editing a connection made by signing in: the form offers the name and
 * nothing else, and saving it keeps the token and the app registration.
 */
class ConnectionUiControllerTest {

    /** Without a signed-in user the controller acts for this one. */
    private static final UserId ME = UserId.of("mc_user");

    private static final Schema MAILBOX = Schema.object()
            .prop("host", Schema.string().description("Your IMAP server."))
            .prop("password", Schema.string().format(Schema.Format.PASSWORD))
            .require("host", "password");

    /** A provider that can be filled in by hand or signed in to. */
    private static final ConnectionSpec EMAIL = ConnectionSpec.form("email", "Mailbox", MAILBOX)
            .acquire(Acquisition.OAuth.of("ms-graph", "Mail.Read"))
            .allowingSeveral();

    private final ConnectionService service = new ConnectionService(new InMemoryConnectionRepository());
    private final ConnectionUiController controller = new ConnectionUiController(
            new ToolConnections(provider(registry(EMAIL)), provider(service)));

    @Test
    void the_edit_form_of_a_signed_in_connection_offers_the_name_only() throws Exception {
        Connection signedIn = signedIn();

        String json = json(controller.edit(null, signedIn.id().value()));

        assertThat(json).contains("\"label\"").contains("Signed in at the provider")
                .doesNotContain("\"host\"").doesNotContain("\"password\"");
    }

    @Test
    void saving_the_edit_of_a_signed_in_connection_keeps_its_token_and_app_registration() {
        Connection signedIn = signedIn();

        // Even a body that carries the schema's fields changes the name only.
        controller.update(null, signedIn.id().value(),
                Map.of("label", "Work", "host", "imap.example.com", "password", ""));

        Connection saved = service.find(ME, signedIn.id()).orElseThrow();
        assertThat(saved.label()).isEqualTo("Work");
        assertThat(saved.key()).isEqualTo(signedIn.key());
        assertThat(saved.credentials()).isEqualTo(signedIn.credentials());
        assertThat(saved.settings()).isEqualTo(signedIn.settings());
    }

    @Test
    void a_hand_filled_connection_still_gets_the_whole_form() throws Exception {
        Connection typed = service.add(ME, "email", "Privat",
                Map.of("host", "imap.example.com", "password", "s3cret"), Set.of("password"));

        String json = json(controller.edit(null, typed.id().value()));

        assertThat(json).contains("\"host\"").contains("\"password\"").doesNotContain("Signed in at the provider");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private Connection signedIn() {
        return service.attach(ME, "email", "Microsoft",
                new OAuth2UserCreds("at-1", "rt-1", Instant.now().plusSeconds(3600), List.of(), Map.of()),
                Map.of("oauthProvider", "ms-graph"));
    }

    private static String json(Object value) throws Exception {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
    }

    private static ToolRegistry registry(ConnectionSpec... specs) {
        List<ConnectionSpec> declared = List.of(specs);
        return new ToolRegistry() {
            @Override public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
                return Optional.empty();
            }
            @Override public List<ConnectionSpec> connectionSpecs() { return declared; }
        };
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return value; }
        };
    }
}
