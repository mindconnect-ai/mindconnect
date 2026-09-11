package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpCaller;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpProbeResult;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.mcp.gateway.McpResult;
import ai.mindconnect.mcp.gateway.McpServerInfo;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpTarget;
import ai.mindconnect.mcp.gateway.McpTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool name prefix is the head of every tool name a server contributes,
 * and agents bind those names. Renaming it silently orphans their bindings,
 * the operator's tool settings and any standing approval — so after creation
 * it is fixed (concept 23 §5).
 */
class McpServerPrefixIsFixedTest {


    private final FakeAdmin admin = new FakeAdmin();
    private final McpGatewayUiController controller =
            new McpGatewayUiController(admin, new SilentGateway(), null);

    private static McpTarget target() {
        return new McpTarget.Process(List.of("/bin/echo", "hi"), Map.of());
    }

    private static Map<String, Object> body(String prefix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "gmail");          // on an edit the path wins; harmless here
        body.put("displayName", "Gmail");
        body.put("enabled", true);
        body.put("target", McpTargetForm.toJson(target()));
        if (prefix != null) {
            body.put("toolNamePrefix", prefix);
        }
        return body;
    }

    private static String json(Object page) throws Exception {
        return new ObjectMapper().writeValueAsString(page);
    }

    @Test
    void a_new_registration_takes_the_prefix_from_the_form() {
        controller.save(null, body("gmail"));

        assertThat(admin.saved).singleElement()
                .satisfies(r -> assertThat(r.toolNamePrefix()).isEqualTo("gmail"));
    }

    @Test
    void editing_without_the_field_keeps_the_stored_prefix() throws Exception {
        // The form shows the prefix read-only, and a read-only field is not
        // submitted — so the stored value has to be the answer, or every edit
        // would fail on "Tool name prefix is required".
        admin.store(new McpServerRegistration(McpServerId.of("gmail"), "Gmail", null, true, "gmail",
                target(), null));

        Object page = controller.save("gmail", body(null));

        assertThat(json(page)).doesNotContain("required");
        assertThat(admin.saved).singleElement()
                .satisfies(r -> assertThat(r.toolNamePrefix()).isEqualTo("gmail"));
    }

    @Test
    void a_submitted_change_is_refused_and_nothing_is_written() throws Exception {
        // Reaching past the form — the API, a script. Refused rather than
        // ignored: a field that is silently dropped is worse than one that is
        // missing, and this one would take agent bindings with it.
        admin.store(new McpServerRegistration(McpServerId.of("gmail"), "Gmail", null, true, "gmail",
                target(), null));

        String page = json(controller.save("gmail", body("googlemail")));

        assertThat(page).contains("fixed after creation");
        assertThat(page).contains("'gmail'").contains("'googlemail'");
        assertThat(admin.saved).as("nothing written").isEmpty();
    }

    @Test
    void the_same_prefix_sent_again_is_not_a_change() throws Exception {
        admin.store(new McpServerRegistration(McpServerId.of("gmail"), "Gmail", null, true, "gmail",
                target(), null));

        assertThat(json(controller.save("gmail", body("gmail")))).doesNotContain("fixed after creation");
        assertThat(admin.saved).hasSize(1);
    }

    /** Registrations in memory, with what was written kept for the assertions. */
    private static final class FakeAdmin implements McpRegistryAdmin {

        private final Map<String, McpServerRegistration> registrations = new LinkedHashMap<>();
        final List<McpServerRegistration> saved = new ArrayList<>();

        void store(McpServerRegistration registration) {
            registrations.put(registration.id().value(), registration);
        }

        @Override public List<McpServerRegistration> all() {
            return List.copyOf(registrations.values());
        }

        @Override public Optional<McpServerRegistration> findById(McpServerId id) {
            return Optional.ofNullable(registrations.get(id.value()));
        }

        @Override public void save(McpServerRegistration registration) {
            saved.add(registration);
            store(registration);
        }

        @Override public void delete(McpServerId id) {
            registrations.remove(id.value());
        }

        @Override public McpProbeResult probe(McpServerRegistration draft) {
            return McpProbeResult.success(List.of(), 0);
        }

        @Override public McpDiscovery discovery(McpServerId id) {
            return McpDiscovery.never();
        }

        @Override public void refresh(McpServerId id) { }
    }

    /** Answers the list view's tool count without contacting anything. */
    private static final class SilentGateway implements McpGateway {
        @Override public List<McpServerInfo> servers() { return List.of(); }
        @Override public List<McpTool> tools(McpServerId server) { return List.of(); }
        @Override public McpResult call(McpCaller caller, McpServerId server, String toolName,
                                        Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }
        @Override public void release(McpCaller caller) { }
        @Override public long catalogVersion() { return 0L; }
    }
}
